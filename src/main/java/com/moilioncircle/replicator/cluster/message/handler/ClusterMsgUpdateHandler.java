package com.moilioncircle.replicator.cluster.message.handler;

import com.moilioncircle.replicator.cluster.ClusterLink;
import com.moilioncircle.replicator.cluster.ClusterNode;
import com.moilioncircle.replicator.cluster.gossip.ThinGossip1;
import com.moilioncircle.replicator.cluster.message.ClusterMsg;

import static com.moilioncircle.replicator.cluster.ClusterConstants.CLUSTER_TODO_SAVE_CONFIG;
import static com.moilioncircle.replicator.cluster.ClusterConstants.nodeIsSlave;

/**
 * Created by Baoyi Chen on 2017/7/13.
 */
public class ClusterMsgUpdateHandler extends AbstractClusterMsgHandler {
    public ClusterMsgUpdateHandler(ThinGossip1 gossip) {
        super(gossip);
    }

    @Override
    public boolean handle(ClusterNode sender, ClusterLink link, ClusterMsg hdr) {
        logger.debug("Update packet received: " + link.node);
        long reportedConfigEpoch = hdr.data.nodecfg.configEpoch;
        if (sender == null) return true;
        ClusterNode n = gossip.nodeManager.clusterLookupNode(hdr.data.nodecfg.nodename);
        if (n == null) return true;
        if (n.configEpoch >= reportedConfigEpoch) return true;

        if (nodeIsSlave(n)) gossip.clusterSetNodeAsMaster(n);

        n.configEpoch = reportedConfigEpoch;
        gossip.clusterDoBeforeSleep(CLUSTER_TODO_SAVE_CONFIG);

        gossip.clusterUpdateSlotsConfigWith(n, reportedConfigEpoch, hdr.data.nodecfg.slots);
        return true;
    }
}