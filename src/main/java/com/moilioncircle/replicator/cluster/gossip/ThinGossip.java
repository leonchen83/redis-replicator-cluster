/*
 * Copyright 2016 leon chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moilioncircle.replicator.cluster.gossip;

import com.moilioncircle.replicator.cluster.*;
import com.moilioncircle.replicator.cluster.codec.ClusterMsgDecoder;
import com.moilioncircle.replicator.cluster.codec.ClusterMsgEncoder;
import com.moilioncircle.replicator.cluster.config.ConfigInfo;
import com.moilioncircle.replicator.cluster.message.ClusterMsg;
import com.moilioncircle.replicator.cluster.message.ClusterMsgDataGossip;
import com.moilioncircle.replicator.cluster.message.Message;
import com.moilioncircle.replicator.cluster.message.handler.ClusterMsgHandler;
import com.moilioncircle.replicator.cluster.util.net.NioBootstrapConfiguration;
import com.moilioncircle.replicator.cluster.util.net.NioBootstrapImpl;
import com.moilioncircle.replicator.cluster.util.net.session.SessionImpl;
import com.moilioncircle.replicator.cluster.util.net.transport.Transport;
import com.moilioncircle.replicator.cluster.util.net.transport.TransportListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

import static com.moilioncircle.replicator.cluster.ClusterConstants.*;
import static com.moilioncircle.replicator.cluster.config.ConfigInfo.valueOf;
import static com.moilioncircle.replicator.cluster.gossip.ClusterSlotManger.bitmapTestBit;
import static java.util.Comparator.comparingLong;

/**
 * @author Leon Chen
 * @since 2.1.0
 */
public class ThinGossip {
    private static final Log logger = LogFactory.getLog(ThinGossip.class);

    ExecutorService file;
    ExecutorService worker;
    ScheduledExecutorService executor;
    public Server server = new Server();

    public Client client;
    public ClusterMsgManager msgManager;
    public ClusterSlotManger slotManger;
    public ClusterNodeManager nodeManager;
    public ClusterConfiguration configuration;
    public ClusterConfigManager configManager;
    public ReplicationManager replicationManager;
    public ClusterBlacklistManager blacklistManager;
    public ClusterConnectionManager connectionManager;
    public ClusterMsgHandlerManager msgHandlerManager;

    public ThinGossip(ClusterConfiguration configuration) {
        this.file = Executors.newSingleThreadExecutor();
        this.worker = Executors.newSingleThreadExecutor();
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.configuration = configuration;
        this.configuration.validate();
        this.msgManager = new ClusterMsgManager(this);
        this.slotManger = new ClusterSlotManger(this);
        this.nodeManager = new ClusterNodeManager(this);
        this.configManager = new ClusterConfigManager(this);
        this.replicationManager = new ReplicationManager(this);
        this.connectionManager = new ClusterConnectionManager();
        this.blacklistManager = new ClusterBlacklistManager(this);
        this.msgHandlerManager = new ClusterMsgHandlerManager(this);
        this.client = new Client(this);
    }

    public void start() {
        this.clusterInit();
        client.clientInit();
        executor.scheduleWithFixedDelay(() -> {
            ConfigInfo oldInfo = valueOf(server.cluster);
            clusterCron();
            ConfigInfo newInfo = valueOf(server.cluster);
            if (!oldInfo.equals(newInfo)) file.submit(() -> configManager.clusterSaveConfig(newInfo));
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    public void clusterInit() {
        server.cluster = new ClusterState();
        if (!configManager.clusterLoadConfig(configuration.getClusterConfigfile())) {
            server.myself = server.cluster.myself = nodeManager.createClusterNode(null, CLUSTER_NODE_MYSELF | CLUSTER_NODE_MASTER);
            logger.info("No cluster configuration found, I'm " + server.myself.name);
            nodeManager.clusterAddNode(server.myself);
            ConfigInfo info = valueOf(server.cluster);
            file.submit(() -> configManager.clusterSaveConfig(info));
        }

        NioBootstrapImpl<Message> cfd = new NioBootstrapImpl<>(true, new NioBootstrapConfiguration());
        cfd.setEncoder(ClusterMsgEncoder::new);
        cfd.setDecoder(ClusterMsgDecoder::new);
        cfd.setup();
        cfd.setTransportListener(new TransportListener<Message>() {
            @Override
            public void onConnected(Transport<Message> transport) {
                logger.info("[acceptor] > " + transport.toString());
                ClusterLink link = connectionManager.createClusterLink(null);
                link.fd = new SessionImpl<>(transport);
                server.cfd.put(transport, link);
            }

            @Override
            public void onMessage(Transport<Message> transport, Message message) {
                executor.execute(() -> {
                    ConfigInfo oldInfo = valueOf(server.cluster);
                    clusterProcessPacket(server.cfd.get(transport), message);
                    ConfigInfo newInfo = valueOf(server.cluster);
                    if (!oldInfo.equals(newInfo)) file.submit(() -> configManager.clusterSaveConfig(newInfo));
                });
            }

            @Override
            public void onDisconnected(Transport<Message> transport, Throwable cause) {
                logger.info("[acceptor] < " + transport.toString());
                ClusterLink link = server.cfd.remove(transport);
                connectionManager.freeClusterLink(link);
            }
        });
        try {
            cfd.connect(null, configuration.getClusterAnnounceBusPort()).get();
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            } else {
                throw new UnsupportedOperationException(e.getCause());
            }
        }

        server.myself.port = configuration.getClusterAnnouncePort();
        server.myself.cport = configuration.getClusterAnnounceBusPort();
    }

    public void clusterReset(boolean hard) {
        if (nodeIsSlave(server.myself)) {
            clusterSetNodeAsMaster(server.myself);
            replicationManager.replicationUnsetMaster();
        }

        for (int i = 0; i < CLUSTER_SLOTS; i++)
            slotManger.clusterDelSlot(i);

        List<ClusterNode> nodes = new ArrayList<>(server.cluster.nodes.values());
        for (ClusterNode node : nodes) {
            if (node.equals(server.myself)) continue;
            nodeManager.clusterDelNode(node);
        }
        if (!hard) return;

        server.cluster.currentEpoch = 0;
        server.cluster.lastVoteEpoch = 0;
        server.myself.configEpoch = 0;
        logger.warn("configEpoch set to 0 via CLUSTER RESET HARD");
        String oldname = server.myself.name;
        server.cluster.nodes.remove(oldname);
        server.myself.name = nodeManager.getRandomHexChars();
        nodeManager.clusterAddNode(server.myself);
        logger.info("Node hard reset, now I'm " + server.myself.name);
    }

    public long clusterGetMaxEpoch() {
        return server.cluster.nodes.values().stream().
                max(comparingLong(x -> x.configEpoch)).
                map(e -> e.configEpoch).orElse(server.cluster.currentEpoch);
    }

    public void clusterHandleConfigEpochCollision(ClusterNode sender) {
        if (sender.configEpoch != server.myself.configEpoch || nodeIsSlave(sender) || nodeIsSlave(server.myself))
            return;
        if (sender.name.compareTo(server.myself.name) <= 0) return;
        server.cluster.currentEpoch++;
        server.myself.configEpoch = server.cluster.currentEpoch;
        logger.debug("WARNING: configEpoch collision with node " + sender.name + ". configEpoch set to " + server.myself.configEpoch);
    }

    public void markNodeAsFailingIfNeeded(ClusterNode node) {
        int neededQuorum = server.cluster.size / 2 + 1;

        if (!nodePFailed(node) || nodeFailed(node)) return;

        int failures = nodeManager.clusterNodeFailureReportsCount(node);

        if (nodeIsMaster(server.myself)) failures++;
        if (failures < neededQuorum) return;

        logger.info("Marking node " + node.name + " as failing (quorum reached).");

        node.flags &= ~CLUSTER_NODE_PFAIL;
        node.flags |= CLUSTER_NODE_FAIL;
        node.failTime = System.currentTimeMillis();

        if (nodeIsMaster(server.myself)) msgManager.clusterSendFail(node.name);
    }

    public void clearNodeFailureIfNeeded(ClusterNode node) {
        long now = System.currentTimeMillis();

        if (nodeIsSlave(node) || node.numslots == 0) {
            logger.info("Clear FAIL state for node " + node.name + ": " + (nodeIsSlave(node) ? "slave" : "master without slots") + " is reachable again.");
            node.flags &= ~CLUSTER_NODE_FAIL;
        }

        if (nodeIsMaster(node) && node.numslots > 0 && now - node.failTime > configuration.getClusterNodeTimeout() * CLUSTER_FAIL_UNDO_TIME_MULT) {
            logger.info("Clear FAIL state for node " + node.name + ": is reachable again and nobody is serving its slots after some time.");
            node.flags &= ~CLUSTER_NODE_FAIL;
        }
    }

    public boolean clusterHandshakeInProgress(String ip, int port, int cport) {
        return server.cluster.nodes.values().stream().
                anyMatch(x -> nodeInHandshake(x) && x.ip.equalsIgnoreCase(ip) && x.port == port && x.cport == cport);
    }

    public boolean clusterStartHandshake(String ip, int port, int cport) {
        if (clusterHandshakeInProgress(ip, port, cport)) return false;

        ClusterNode n = nodeManager.createClusterNode(null, CLUSTER_NODE_HANDSHAKE | CLUSTER_NODE_MEET);
        n.ip = ip;
        n.port = port;
        n.cport = cport;
        nodeManager.clusterAddNode(n);
        return true;
    }

    public void clusterProcessGossipSection(ClusterMsg hdr, ClusterLink link) {
        List<ClusterMsgDataGossip> gs = hdr.data.gossip;
        ClusterNode sender = link.node != null ? link.node : nodeManager.clusterLookupNode(hdr.sender);
        for (ClusterMsgDataGossip g : gs) {
            int flags = g.flags;
            String ci = configManager.representClusterNodeFlags(flags);
            logger.debug("GOSSIP " + g.nodename + " " + g.ip + ":" + g.port + "@" + g.cport + " " + ci);

            ClusterNode node = nodeManager.clusterLookupNode(g.nodename);

            if (node == null) {
                if (sender != null && (flags & CLUSTER_NODE_NOADDR) == 0 && !blacklistManager.clusterBlacklistExists(g.nodename)) {
                    clusterStartHandshake(g.ip, g.port, g.cport);
                }
                continue;
            }

            if (sender != null && nodeIsMaster(sender) && !node.equals(server.myself)) {
                if ((flags & (CLUSTER_NODE_FAIL | CLUSTER_NODE_PFAIL)) != 0) {
                    if (nodeManager.clusterNodeAddFailureReport(node, sender)) {
                        logger.debug("Node " + sender.name + " reported node " + node.name + " as not reachable.");
                    }
                    markNodeAsFailingIfNeeded(node);
                } else if (nodeManager.clusterNodeDelFailureReport(node, sender)) {
                    logger.debug("Node " + sender.name + " reported node " + node.name + " is back online.");
                }
            }

            if ((flags & (CLUSTER_NODE_FAIL | CLUSTER_NODE_PFAIL)) == 0 && node.pingSent == 0 && nodeManager.clusterNodeFailureReportsCount(node) == 0) {
                long pongtime = g.pongReceived;
                if (pongtime <= (System.currentTimeMillis() + 500) && pongtime > node.pongReceived) {
                    node.pongReceived = pongtime;
                }
            }

            if ((node.flags & (CLUSTER_NODE_FAIL | CLUSTER_NODE_PFAIL)) != 0 && (flags & CLUSTER_NODE_NOADDR) == 0 && (flags & (CLUSTER_NODE_FAIL | CLUSTER_NODE_PFAIL)) == 0 &&
                    (!node.ip.equalsIgnoreCase(g.ip) || node.port != g.port || node.cport != g.cport)) {
                if (node.link != null) connectionManager.freeClusterLink(node.link);
                node.ip = g.ip;
                node.port = g.port;
                node.cport = g.cport;
                node.flags &= ~CLUSTER_NODE_NOADDR;
            }
        }
    }

    public boolean nodeUpdateAddressIfNeeded(ClusterNode node, ClusterLink link, ClusterMsg hdr) {
        int port = hdr.port;
        int cport = hdr.cport;
        if (link.equals(node.link)) return false;

        String ip = link.fd.getRemoteAddress(hdr.myip);

        if (node.port == port && node.cport == cport && ip.equals(node.ip)) return false;

        node.ip = ip;
        node.port = port;
        node.cport = cport;

        if (node.link != null) connectionManager.freeClusterLink(node.link);
        logger.warn("Address updated for node " + node.name + ", now " + node.ip + ":" + node.port);

        if (nodeIsSlave(server.myself) && server.myself.slaveof.equals(node)) {
            replicationManager.replicationSetMaster(node);
        }
        return true;
    }

    public void clusterSetNodeAsMaster(ClusterNode n) {
        if (nodeIsMaster(n)) return;

        if (n.slaveof != null) {
            nodeManager.clusterNodeRemoveSlave(n.slaveof, n);
            if (n.equals(server.myself)) n.flags |= CLUSTER_NODE_MIGRATE_TO;
        }

        n.flags &= ~CLUSTER_NODE_SLAVE;
        n.flags |= CLUSTER_NODE_MASTER;
        n.slaveof = null;
    }

    public void clusterUpdateSlotsConfigWith(ClusterNode sender, long senderConfigEpoch, byte[] slots) {
        ClusterNode newmaster = null;
        ClusterNode curmaster = nodeIsMaster(server.myself) ? server.myself : server.myself.slaveof;
        if (sender.equals(server.myself)) {
            logger.warn("Discarding UPDATE message about myself.");
            return;
        }

        for (int i = 0; i < CLUSTER_SLOTS; i++) {
            if (bitmapTestBit(slots, i)) {
                if (server.cluster.slots[i] != null && server.cluster.slots[i].equals(sender)) continue;
                if (server.cluster.slots[i] == null || server.cluster.slots[i].configEpoch < senderConfigEpoch) {
                    if (server.cluster.slots[i] != null && server.cluster.slots[i].equals(curmaster))
                        newmaster = sender;
                    slotManger.clusterDelSlot(i);
                    slotManger.clusterAddSlot(sender, i);
                }
            }
        }

        if (newmaster != null && curmaster.numslots == 0) {
            logger.warn("Configuration change detected. Reconfiguring myself as a replica of " + sender.name);
            clusterSetMaster(sender);
        }
    }

    public boolean clusterProcessPacket(ClusterLink link, Message message) {
        ClusterMsg hdr = (ClusterMsg) message;
        int totlen = hdr.totlen;
        int type = hdr.type;

        if (type < CLUSTERMSG_TYPE_COUNT) {
            server.cluster.statsBusMessagesReceived[type]++;
        }

        if (hdr.ver != CLUSTER_PROTO_VER) return true;

        ClusterNode sender = nodeManager.clusterLookupNode(hdr.sender);
        if (sender != null && !nodeInHandshake(sender)) {
            if (hdr.currentEpoch > server.cluster.currentEpoch) {
                server.cluster.currentEpoch = hdr.currentEpoch;
            }
            if (hdr.configEpoch > sender.configEpoch) {
                sender.configEpoch = hdr.configEpoch;
            }
        }

        ClusterMsgHandler handler = msgHandlerManager.get(type);
        if (handler == null) {
            logger.warn("Received unknown packet type: " + type);
        } else {
            handler.handle(sender, link, hdr);
        }
        clusterUpdateState();
        return true;
    }

    public void clusterSendFailoverAuthIfNeeded(ClusterNode node, ClusterMsg request) {
        ClusterNode master = node.slaveof;
        long requestCurrentEpoch = request.currentEpoch;
        long requestConfigEpoch = request.configEpoch;
        byte[] claimedSlots = request.myslots;
        boolean forceAck = (request.mflags[0] & CLUSTERMSG_FLAG0_FORCEACK) != 0;

        if (nodeIsSlave(server.myself) || server.myself.numslots == 0) return;

        if (requestCurrentEpoch < server.cluster.currentEpoch) {
            logger.warn("Failover auth denied to " + node.name + ": reqEpoch " + requestCurrentEpoch + " < curEpoch(" + server.cluster.currentEpoch + ")");
            return;
        }

        if (server.cluster.lastVoteEpoch == server.cluster.currentEpoch) {
            logger.warn("Failover auth denied to " + node.name + ": already voted for epoch " + server.cluster.currentEpoch);
            return;
        }

        if (nodeIsMaster(node) || master == null || (!nodeFailed(master) && !forceAck)) {
            if (nodeIsMaster(node)) {
                logger.warn("Failover auth denied to " + node.name + ": it is a master node");
            } else if (master == null) {
                logger.warn("Failover auth denied to " + node.name + ": I don't know its master");
            } else if (!nodeFailed(master)) {
                logger.warn("Failover auth denied to " + node.name + ": its master is up");
            }
            return;
        }

        if (System.currentTimeMillis() - node.slaveof.votedTime < configuration.getClusterNodeTimeout() * 2) {
            logger.warn("Failover auth denied to " + node.name + ": can't vote about this master before " + (configuration.getClusterNodeTimeout() * 2 - (System.currentTimeMillis() - node.slaveof.votedTime)) + " milliseconds");
            return;
        }

        for (int i = 0; i < CLUSTER_SLOTS; i++) {
            if (!bitmapTestBit(claimedSlots, i)) continue;
            if (server.cluster.slots[i] == null || server.cluster.slots[i].configEpoch <= requestConfigEpoch)
                continue;

            logger.warn("Failover auth denied to " + node.name + ": slot %d epoch (" + server.cluster.slots[i].configEpoch + ") > reqEpoch (" + requestConfigEpoch + ")");
            return;
        }

        msgManager.clusterSendFailoverAuth(node);
        server.cluster.lastVoteEpoch = server.cluster.currentEpoch;
        node.slaveof.votedTime = System.currentTimeMillis();
        logger.warn("Failover auth granted to " + node.name + " for epoch " + server.cluster.currentEpoch);
    }

    public void clusterUpdateState() {
        if (server.firstCallTime == 0) server.firstCallTime = System.currentTimeMillis();
        if (nodeIsMaster(server.myself)
                && server.cluster.state == CLUSTER_FAIL
                && System.currentTimeMillis() - server.firstCallTime < CLUSTER_WRITABLE_DELAY)
            return;

        byte newState = CLUSTER_OK;

        if (configuration.isClusterRequireFullCoverage()) {
            for (int i = 0; i < CLUSTER_SLOTS; i++) {
                if (server.cluster.slots[i] == null || (server.cluster.slots[i].flags & CLUSTER_NODE_FAIL) != 0) {
                    newState = CLUSTER_FAIL;
                    break;
                }
            }
        }

        int reachableMasters = 0;
        server.cluster.size = 0;
        for (ClusterNode node : server.cluster.nodes.values()) {
            if (nodeIsMaster(node) && node.numslots > 0) {
                server.cluster.size++;
                if ((node.flags & (CLUSTER_NODE_FAIL | CLUSTER_NODE_PFAIL)) == 0)
                    reachableMasters++;
            }
        }

        int neededQuorum = (server.cluster.size / 2) + 1;

        if (reachableMasters < neededQuorum) {
            newState = CLUSTER_FAIL;
            server.amongMinorityTime = System.currentTimeMillis();
        }

        if (newState != server.cluster.state) {
            long rejoinDelay = configuration.getClusterNodeTimeout();

            if (rejoinDelay > CLUSTER_MAX_REJOIN_DELAY)
                rejoinDelay = CLUSTER_MAX_REJOIN_DELAY;
            if (rejoinDelay < CLUSTER_MIN_REJOIN_DELAY)
                rejoinDelay = CLUSTER_MIN_REJOIN_DELAY;

            if (newState == CLUSTER_OK
                    && nodeIsMaster(server.myself)
                    && System.currentTimeMillis() - server.amongMinorityTime < rejoinDelay) {
                return;
            }

            logger.warn("Cluster state changed: " + (newState == CLUSTER_OK ? "ok" : "fail"));
            server.cluster.state = newState;
        }
    }

    public void clusterSetMaster(ClusterNode n) {
        if (nodeIsMaster(server.myself)) {
            server.myself.flags &= ~(CLUSTER_NODE_MASTER | CLUSTER_NODE_MIGRATE_TO);
            server.myself.flags |= CLUSTER_NODE_SLAVE;
        } else if (server.myself.slaveof != null) {
            nodeManager.clusterNodeRemoveSlave(server.myself.slaveof, server.myself);
        }
        server.myself.slaveof = n;
        nodeManager.clusterNodeAddSlave(n, server.myself);
        replicationManager.replicationSetMaster(n);
    }

    public void clusterCron() {
        try {
            long now = System.currentTimeMillis();
            server.iteration++;

            String currIp = configuration.getClusterAnnounceIp();
            if (!Objects.equals(server.prevIp, currIp)) {
                server.prevIp = currIp;
                if (currIp != null) server.myself.ip = currIp;
                else server.myself.ip = null;
            }

            long handshakeTimeout = configuration.getClusterNodeTimeout();
            if (handshakeTimeout < 1000) handshakeTimeout = 1000;
            server.cluster.statsPfailNodes = 0;
            List<ClusterNode> nodes = new ArrayList<>(server.cluster.nodes.values());
            for (ClusterNode node : nodes) {
                if ((node.flags & (CLUSTER_NODE_MYSELF | CLUSTER_NODE_NOADDR)) != 0) continue;

                if ((node.flags & CLUSTER_NODE_PFAIL) != 0)
                    server.cluster.statsPfailNodes++;

                if (nodeInHandshake(node) && now - node.ctime > handshakeTimeout) {
                    nodeManager.clusterDelNode(node);
                    continue;
                }

                if (node.link == null) {
                    final ClusterLink link = connectionManager.createClusterLink(node);
                    NioBootstrapImpl<Message> fd = new NioBootstrapImpl<>(false, new NioBootstrapConfiguration());
                    fd.setEncoder(ClusterMsgEncoder::new);
                    fd.setDecoder(ClusterMsgDecoder::new);
                    fd.setup();
                    fd.setTransportListener(new TransportListener<Message>() {
                        @Override
                        public void onConnected(Transport<Message> transport) {
                            logger.info("[initiator] > " + transport.toString());
                        }

                        @Override
                        public void onMessage(Transport<Message> transport, Message message) {
                            executor.execute(() -> {
                                ConfigInfo oldInfo = valueOf(server.cluster);
                                clusterProcessPacket(link, message);
                                ConfigInfo newInfo = valueOf(server.cluster);
                                if (!oldInfo.equals(newInfo))
                                    file.submit(() -> configManager.clusterSaveConfig(newInfo));
                            });
                        }

                        @Override
                        public void onDisconnected(Transport<Message> transport, Throwable cause) {
                            logger.info("[initiator] < " + transport.toString());
                            connectionManager.freeClusterLink(link);
                            fd.shutdown();
                        }
                    });
                    try {
                        fd.connect(node.ip, node.cport).get();
                        link.fd = new SessionImpl<>(fd.getTransport());
                    } catch (InterruptedException | ExecutionException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        if (node.pingSent == 0) node.pingSent = System.currentTimeMillis();
                        fd.shutdown();
                        continue;
                    }
                    node.link = link;
                    link.ctime = System.currentTimeMillis();
                    long oldPingSent = node.pingSent;
                    msgManager.clusterSendPing(link, (node.flags & CLUSTER_NODE_MEET) != 0 ? CLUSTERMSG_TYPE_MEET : CLUSTERMSG_TYPE_PING);
                    if (oldPingSent != 0) node.pingSent = oldPingSent;
                    node.flags &= ~CLUSTER_NODE_MEET;
                }
            }

            long minPong = 0;
            ClusterNode minPongNode = null;
            if (server.iteration % 10 == 0) {
                for (int i = 0; i < 5; i++) {
                    List<ClusterNode> list = new ArrayList<>(server.cluster.nodes.values());
                    ClusterNode t = list.get(ThreadLocalRandom.current().nextInt(list.size()));

                    if (t.link == null || t.pingSent != 0) continue;
                    if ((t.flags & (CLUSTER_NODE_MYSELF | CLUSTER_NODE_HANDSHAKE)) != 0) continue;
                    if (minPongNode == null || minPong > t.pongReceived) {
                        minPongNode = t;
                        minPong = t.pongReceived;
                    }
                }
                if (minPongNode != null) {
                    logger.debug("Pinging node " + minPongNode.name);
                    msgManager.clusterSendPing(minPongNode.link, CLUSTERMSG_TYPE_PING);
                }
            }

            boolean update = false;
            int maxSlaves = 0, thisSlaves = 0, orphanedMasters = 0;
            for (ClusterNode node : server.cluster.nodes.values()) {
                now = System.currentTimeMillis();

                if ((node.flags & (CLUSTER_NODE_MYSELF | CLUSTER_NODE_NOADDR | CLUSTER_NODE_HANDSHAKE)) != 0)
                    continue;

                if (nodeIsSlave(server.myself) && nodeIsMaster(node) && !nodeFailed(node)) {
                    int slaves = nodeManager.clusterCountNonFailingSlaves(node);

                    if (slaves == 0 && node.numslots > 0 && (node.flags & CLUSTER_NODE_MIGRATE_TO) != 0) {
                        orphanedMasters++;
                    }
                    if (slaves > maxSlaves) maxSlaves = slaves;
                    if (server.myself.slaveof.equals(node))
                        thisSlaves = slaves;
                }

                if (node.link != null
                        && now - node.link.ctime > configuration.getClusterNodeTimeout()
                        && node.pingSent != 0 && node.pongReceived < node.pingSent
                        && now - node.pingSent > configuration.getClusterNodeTimeout() / 2) {
                    connectionManager.freeClusterLink(node.link);
                }

                if (node.link != null && node.pingSent == 0 && (now - node.pongReceived) > configuration.getClusterNodeTimeout() / 2) {
                    msgManager.clusterSendPing(node.link, CLUSTERMSG_TYPE_PING);
                    continue;
                }

                if (node.pingSent == 0) continue;

                if (now - node.pingSent > configuration.getClusterNodeTimeout() && (node.flags & (CLUSTER_NODE_PFAIL | CLUSTER_NODE_FAIL)) == 0) {
                    logger.debug("*** NODE " + node.name + " possibly failing");
                    node.flags |= CLUSTER_NODE_PFAIL;
                    update = true;
                }
            }

            if (nodeIsSlave(server.myself) && server.masterHost == null && server.myself.slaveof != null && nodeHasAddr(server.myself.slaveof)) {
                replicationManager.replicationSetMaster(server.myself.slaveof);
            }

            if (nodeIsSlave(server.myself) && orphanedMasters != 0 && maxSlaves >= 2 && thisSlaves == maxSlaves) {
                clusterHandleSlaveMigration(maxSlaves);
            }

            if (update || server.cluster.state == CLUSTER_FAIL) clusterUpdateState();
        } catch (Throwable e) {
            logger.error("error", e);
        }
    }

    public void clusterHandleSlaveMigration(int maxSlaves) {
        if (server.cluster.state != CLUSTER_OK) return;
        if (server.myself.slaveof == null) return;
        int slaves = (int) server.myself.slaveof.slaves.stream().
                filter(x -> !nodeFailed(x) && !nodePFailed(x)).count();
        if (slaves <= configuration.getClusterMigrationBarrier()) return;

        ClusterNode candidate = server.myself;
        ClusterNode target = null;
        for (ClusterNode node : server.cluster.nodes.values()) {
            slaves = 0;
            boolean isOrphaned = true;

            if (nodeIsSlave(node) || nodeFailed(node)) isOrphaned = false;
            if ((node.flags & CLUSTER_NODE_MIGRATE_TO) == 0) isOrphaned = false;

            if (nodeIsMaster(node)) slaves = nodeManager.clusterCountNonFailingSlaves(node);
            if (slaves > 0) isOrphaned = false;

            if (isOrphaned) {
                if (target == null && node.numslots > 0) target = node;
                if (node.orphanedTime == 0) node.orphanedTime = System.currentTimeMillis();
            } else {
                node.orphanedTime = 0;
            }

            if (slaves == maxSlaves) {
                candidate = node.slaves.stream().reduce(server.myself, (a, b) -> a.name.compareTo(b.name) >= 0 ? b : a);
            }
        }

        if (target != null && candidate.equals(server.myself) && (System.currentTimeMillis() - target.orphanedTime) > CLUSTER_SLAVE_MIGRATION_DELAY) {
            logger.warn("Migrating to orphaned master " + target.name);
            clusterSetMaster(target);
        }
    }
}
