package com.moilioncircle.replicator.cluster.manager;

import com.moilioncircle.replicator.cluster.state.ClusterNode;
import com.moilioncircle.replicator.cluster.state.ServerState;

import java.util.AbstractMap;
import java.util.Map;

import static com.moilioncircle.replicator.cluster.ClusterConstants.CLUSTER_BLACKLIST_TTL;

/**
 * Created by Baoyi Chen on 2017/7/13.
 */
public class ClusterBlacklistManager {

    private ServerState server;
    private ClusterManagers managers;

    public ClusterBlacklistManager(ClusterManagers managers) {
        this.managers = managers;
        this.server = managers.server;
    }

    public void clusterBlacklistCleanup() {
        server.cluster.nodesBlackList.values().removeIf(e -> e.getKey() < System.currentTimeMillis());
    }

    public void clusterBlacklistAddNode(ClusterNode node) {
        clusterBlacklistCleanup();
        Map.Entry<Long, ClusterNode> entry = new AbstractMap.SimpleEntry<>(System.currentTimeMillis() + CLUSTER_BLACKLIST_TTL, node);
        server.cluster.nodesBlackList.put(node.name, entry);
    }

    public boolean clusterBlacklistExists(String nodename) {
        clusterBlacklistCleanup();
        return server.cluster.nodesBlackList.containsKey(nodename);
    }
}
