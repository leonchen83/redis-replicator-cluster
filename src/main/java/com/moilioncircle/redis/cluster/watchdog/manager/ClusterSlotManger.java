package com.moilioncircle.redis.cluster.watchdog.manager;

import com.moilioncircle.redis.cluster.watchdog.state.ClusterNode;
import com.moilioncircle.redis.cluster.watchdog.state.ServerState;

import static com.moilioncircle.redis.cluster.watchdog.ClusterConstants.CLUSTER_NODE_MIGRATE_TO;
import static com.moilioncircle.redis.cluster.watchdog.ClusterConstants.CLUSTER_SLOTS;
import static com.moilioncircle.redis.cluster.watchdog.state.States.nodeIsSlave;
import static com.moilioncircle.redis.cluster.watchdog.util.CRC16.crc16;

/**
 * Created by Baoyi Chen on 2017/7/12.
 */
public class ClusterSlotManger {
    private ServerState server;

    public ClusterSlotManger(ClusterManagers managers) {
        this.server = managers.server;
    }

    public static boolean bitmapTestBit(byte[] bitmap, int pos) {
        int offset = pos / 8;
        int bit = pos & 7;
        return (bitmap[offset] & (1 << bit)) != 0;
    }

    public static void bitmapSetBit(byte[] bitmap, int pos) {
        int offset = pos / 8;
        int bit = pos & 7;
        bitmap[offset] |= 1 << bit;
    }

    public static void bitmapClearBit(byte[] bitmap, int pos) {
        int offset = pos / 8;
        int bit = pos & 7;
        bitmap[offset] &= ~(1 << bit);
    }

    public boolean clusterMastersHaveSlaves() {
        int slaves = 0;
        for (ClusterNode node : server.cluster.nodes.values()) {
            if (nodeIsSlave(node)) continue;
            slaves += node.numslaves;
        }
        return slaves != 0;
    }

    public boolean clusterNodeSetSlotBit(ClusterNode n, int slot) {
        boolean old = bitmapTestBit(n.slots, slot);
        bitmapSetBit(n.slots, slot);
        if (old) return old;
        n.numslots++;
        if (n.numslots == 1 && clusterMastersHaveSlaves())
            n.flags |= CLUSTER_NODE_MIGRATE_TO;
        return old;
    }

    public boolean clusterNodeClearSlotBit(ClusterNode n, int slot) {
        boolean old = bitmapTestBit(n.slots, slot);
        bitmapClearBit(n.slots, slot);
        if (old) n.numslots--;
        return old;
    }

    public boolean clusterAddSlot(ClusterNode n, int slot) {
        if (server.cluster.slots[slot] != null) return false;
        clusterNodeSetSlotBit(n, slot);
        server.cluster.slots[slot] = n;
        return true;
    }

    public boolean clusterDelSlot(int slot) {
        ClusterNode n = server.cluster.slots[slot];
        if (n == null) return false;
        clusterNodeClearSlotBit(n, slot);
        server.cluster.slots[slot] = null;
        return true;
    }

    public int clusterDelNodeSlots(ClusterNode node) {
        int deleted = 0;
        for (int j = 0; j < CLUSTER_SLOTS; j++) {
            if (!bitmapTestBit(node.slots, j)) continue;
            clusterDelSlot(j);
            deleted++;
        }
        return deleted;
    }

    public static int keyHashSlot(String key) {
        if (key == null) return 0;
        int st = key.indexOf('{');
        if (st >= 0) {
            int ed = -1;
            for (int i = st + 1, len = key.length(); i < len; i++) {
                if (key.charAt(i) != '}') continue;
                ed = i;
                break;
            }
            if (ed > st + 1) key = key.substring(st + 1, ed);
        }
        return crc16(key.getBytes()) & (CLUSTER_SLOTS - 1);
    }
}
