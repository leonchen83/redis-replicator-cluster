package com.moilioncircle.redis.cluster.watchdog.message.handler;

import com.moilioncircle.redis.cluster.watchdog.manager.ClusterManagers;
import com.moilioncircle.redis.cluster.watchdog.message.ClusterMessage;
import com.moilioncircle.redis.cluster.watchdog.state.ClusterLink;
import com.moilioncircle.redis.cluster.watchdog.state.ClusterNode;

import java.util.Arrays;

import static com.moilioncircle.redis.cluster.watchdog.ClusterConstants.*;
import static com.moilioncircle.redis.cluster.watchdog.manager.ClusterSlotManger.bitmapTestBit;
import static com.moilioncircle.redis.cluster.watchdog.state.States.*;

/**
 * Created by Baoyi Chen on 2017/7/13.
 */
public class ClusterMessagePongHandler extends AbstractClusterMessageHandler {
    public ClusterMessagePongHandler(ClusterManagers gossip) {
        super(gossip);
    }

    @Override
    public boolean handle(ClusterNode sender, ClusterLink link, ClusterMessage hdr) {
        if (logger.isDebugEnabled()) {
            logger.debug("Pong packet received: node:" + link.node + ",sender:" + sender + ",message:" + hdr);
        }

        if (link.node != null) {
            if (nodeInHandshake(link.node)) {
                if (sender != null) {
                    if (managers.configuration.isVerbose())
                        logger.info("Handshake: we already know node " + sender.name + ", updating the address if needed.");
                    nodeUpdateAddressIfNeeded(sender, link, hdr);
                    managers.nodes.clusterDelNode(link.node);
                    return false;
                }

                managers.nodes.clusterRenameNode(link.node, hdr.sender);
                if (managers.configuration.isVerbose())
                    logger.info("Handshake with node " + link.node.name + " completed.");
                link.node.flags &= ~CLUSTER_NODE_HANDSHAKE;
                link.node.flags |= hdr.flags & (CLUSTER_NODE_MASTER | CLUSTER_NODE_SLAVE);
            } else if (!link.node.name.equals(hdr.sender)) {
                logger.debug("PONG contains mismatching sender ID. About node " + link.node.name + " added " + (System.currentTimeMillis() - link.node.ctime) + " ms ago, having flags " + link.node.flags);
                link.node.flags |= CLUSTER_NODE_NOADDR;
                link.node.ip = null;
                link.node.port = 0;
                link.node.cport = 0;
                managers.connections.freeClusterLink(link);
                return false;
            }

            link.node.pongReceived = System.currentTimeMillis();
            link.node.pingSent = 0;

            if (nodePFailed(link.node)) {
                link.node.flags &= ~CLUSTER_NODE_PFAIL;
            } else if (nodeFailed(link.node)) {
                clearNodeFailureIfNeeded(link.node);
            }
        }

        if (sender == null) return true;

        if (hdr.slaveof == null) {
            managers.nodes.clusterSetNodeAsMaster(sender);
        } else {
            ClusterNode master = managers.nodes.clusterLookupNode(hdr.slaveof);

            if (nodeIsMaster(sender)) {
                managers.slots.clusterDelNodeSlots(sender);
                sender.flags &= ~(CLUSTER_NODE_MASTER | CLUSTER_NODE_MIGRATE_TO);
                sender.flags |= CLUSTER_NODE_SLAVE;
            }

            if (master != null && (sender.slaveof == null || !sender.slaveof.equals(master))) {
                if (sender.slaveof != null) managers.nodes.clusterNodeRemoveSlave(sender.slaveof, sender);
                managers.nodes.clusterNodeAddSlave(master, sender);
                sender.slaveof = master;
            }
        }

        boolean dirtySlots = false;
        ClusterNode senderMaster = nodeIsMaster(sender) ? sender : sender.slaveof;
        if (senderMaster != null) {
            dirtySlots = !Arrays.equals(senderMaster.slots, hdr.myslots);
        }

        if (nodeIsMaster(sender) && dirtySlots) {
            clusterUpdateSlotsConfigWith(sender, hdr.configEpoch, hdr.myslots);
        }

        if (dirtySlots) {
            for (int i = 0; i < CLUSTER_SLOTS; i++) {
                if (!bitmapTestBit(hdr.myslots, i)) continue;
                if (server.cluster.slots[i] == null || server.cluster.slots[i].equals(sender)) continue;
                if (server.cluster.slots[i].configEpoch > hdr.configEpoch) {
                    if (managers.configuration.isVerbose())
                        logger.info("Node " + sender.name + " has old slots configuration, sending an UPDATE message about " + server.cluster.slots[i].name);
                    managers.messages.clusterSendUpdate(sender.link, server.cluster.slots[i]);
                    break;
                }
            }
        }

        if (nodeIsMaster(server.myself) && nodeIsMaster(sender) && hdr.configEpoch == server.myself.configEpoch) {
            clusterHandleConfigEpochCollision(sender);
        }

        clusterProcessGossipSection(hdr, link);
        return true;
    }

    public void clearNodeFailureIfNeeded(ClusterNode node) {
        long now = System.currentTimeMillis();

        if (nodeIsSlave(node) || node.numslots == 0) {
            logger.info("Clear FAIL state for node " + node.name + ": " + (nodeIsSlave(node) ? "slave" : "master without slots") + " is reachable again.");
            node.flags &= ~CLUSTER_NODE_FAIL;
        }

        if (nodeIsMaster(node) && node.numslots > 0 && now - node.failTime > managers.configuration.getClusterNodeTimeout() * CLUSTER_FAIL_UNDO_TIME_MULT) {
            logger.info("Clear FAIL state for node " + node.name + ": is reachable again and nobody is serving its slots after some time.");
            node.flags &= ~CLUSTER_NODE_FAIL;
        }
    }
}
