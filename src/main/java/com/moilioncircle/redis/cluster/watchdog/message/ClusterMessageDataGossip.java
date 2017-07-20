package com.moilioncircle.redis.cluster.watchdog.message;

/**
 * Created by Baoyi Chen on 2017/7/6.
 */
public class ClusterMessageDataGossip {
    public String nodename;
    public long pingSent;
    public long pongReceived;
    public String ip;
    public int port;
    public int cport;
    public int flags;
    public byte[] notused1 = new byte[4];

    @Override
    public String toString() {
        return "ClusterMessageDataGossip{" +
                "nodename='" + nodename + '\'' +
                ", pingSent=" + pingSent +
                ", pongReceived=" + pongReceived +
                ", ip='" + ip + '\'' +
                ", port=" + port +
                ", cport=" + cport +
                ", flags=" + flags +
                '}';
    }
}
