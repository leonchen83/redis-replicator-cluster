package com.moilioncircle.replicator.cluster.util.net.transport;

import com.moilioncircle.replicator.cluster.util.concurrent.future.CompletableFuture;
import com.moilioncircle.replicator.cluster.util.concurrent.future.ListenableChannelFuture;
import com.moilioncircle.replicator.cluster.util.net.ConnectionStatus;
import com.moilioncircle.replicator.cluster.util.net.exceptions.OverloadException;
import com.moilioncircle.replicator.cluster.util.net.exceptions.TransportException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static com.moilioncircle.replicator.cluster.util.net.ConnectionStatus.CONNECTED;
import static com.moilioncircle.replicator.cluster.util.net.ConnectionStatus.DISCONNECTED;

/**
 * Created by Baoyi Chen on 2017/7/7.
 */
public class NioTransport<T> extends SimpleChannelInboundHandler<T> implements Transport<T> {

    private long id;
    private volatile TransportListener<T> listener;
    private volatile ChannelHandlerContext context;
    private static AtomicInteger acc = new AtomicInteger();

    public NioTransport(Class<T> clazz, TransportListener<T> listener) {
        super(clazz);
        this.listener = listener;
        this.id = acc.incrementAndGet();
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        if (this.context == null) return null;
        return this.context.channel().remoteAddress();
    }

    @Override
    public SocketAddress getLocalAddress() {
        if (this.context == null) return null;
        return this.context.channel().localAddress();
    }

    @Override
    public ConnectionStatus getStatus() {
        if (this.context == null) return DISCONNECTED;
        return this.context.channel().isActive() ? CONNECTED : DISCONNECTED;
    }

    @Override
    public CompletableFuture<Void> write(T message, boolean flush) {
        if (!flush) {
            return new ListenableChannelFuture<>(context.write(message));
        } else {
            return new ListenableChannelFuture<>(context.writeAndFlush(message));
        }
    }

    @Override
    public CompletableFuture<Void> disconnect(Throwable cause) {
        return new ListenableChannelFuture<>(this.context.close());
    }

    @Override
    public TransportListener<T> setTransportListener(TransportListener<T> listener) {
        TransportListener<T> oldListener = this.listener;
        this.listener = listener;
        return oldListener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(this.context = ctx);
        TransportListener<T> listener = this.listener;
        if (listener != null) listener.onConnected(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        TransportListener<T> listener = this.listener;
        if (listener != null) listener.onDisconnected(this, null);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, T message) throws Exception {
        TransportListener<T> listener = this.listener;
        if (listener != null) listener.onMessage(this, message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof IOException) cause = new TransportException(toString(), cause);
        TransportListener<T> listener = this.listener;
        if (listener != null) listener.onException(this, cause);
    }

    @Override
    public final void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable()) return;
        TransportListener<T> listener = this.listener;
        if (listener != null) listener.onException(this, new OverloadException("overload"));
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("NioTransport[")
                .append("id=").append(this.id)
                .append(",la=").append(getLocalAddress())
                .append(",ra=").append(getRemoteAddress()).append("]").toString();
    }

}
