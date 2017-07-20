package com.moilioncircle.redis.cluster.watchdog.message.handler;

import com.moilioncircle.redis.cluster.watchdog.manager.ClusterManagers;
import com.moilioncircle.redis.cluster.watchdog.message.ClusterMessage;
import com.moilioncircle.redis.cluster.watchdog.state.ClusterLink;
import com.moilioncircle.redis.cluster.watchdog.state.ClusterNode;
import com.moilioncircle.redis.cluster.watchdog.state.States;

/**
 * Created by Baoyi Chen on 2017/7/13.
 */
public class ClusterMessageUpdateHandler extends AbstractClusterMessageHandler {
    public ClusterMessageUpdateHandler(ClusterManagers gossip) {
        super(gossip);
    }

    @Override
    public boolean handle(ClusterNode sender, ClusterLink link, ClusterMessage hdr) {
        logger.debug("Update packet received: " + Thread.currentThread() + ",node:" + link.node + ",sender:" + sender + ",message:" + hdr);
        long reportedConfigEpoch = hdr.data.nodecfg.configEpoch;
        if (sender == null) return true;
        ClusterNode n = managers.nodes.clusterLookupNode(hdr.data.nodecfg.nodename);
        if (n == null) return true;
        if (n.configEpoch >= reportedConfigEpoch) return true;

        if (States.nodeIsSlave(n)) managers.nodes.clusterSetNodeAsMaster(n);

        n.configEpoch = reportedConfigEpoch;

        clusterUpdateSlotsConfigWith(n, reportedConfigEpoch, hdr.data.nodecfg.slots);
        return true;
    }
}