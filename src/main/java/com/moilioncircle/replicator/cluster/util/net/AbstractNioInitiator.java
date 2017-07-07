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

package com.moilioncircle.replicator.cluster.util.net;

import com.moilioncircle.replicator.cluster.util.concurrent.future.CompletableFuture;
import com.moilioncircle.replicator.cluster.util.concurrent.future.ListenableChannelFuture;
import com.moilioncircle.replicator.cluster.util.concurrent.future.ListenableFuture;
import com.moilioncircle.replicator.cluster.util.net.transport.NioTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.TimeUnit;

import static com.sun.javafx.animation.TickCalculation.toMillis;

/**
 * @author Leon Chen
 * @since 2.1.0
 */
public class AbstractNioInitiator<T> extends AbstractNioBootstrap<T> {
    protected Bootstrap bootstrap;
    protected EventLoopGroup workerGroup;
    protected volatile NioTransport<T> transport;

    protected AbstractNioInitiator(Class<T> messageType, NioBootstrapConfiguration configuration) {
        super(messageType, configuration);
    }

    @Override
    public void setup() {
        this.bootstrap = new Bootstrap();
        this.bootstrap.channel(NioSocketChannel.class);
        this.bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) throws Exception {
                final ChannelPipeline p = channel.pipeline();
                p.addLast("encoder", getEncoder().get());
                p.addLast("decoder", getDecoder().get());
                p.addLast("transport", transport = new NioTransport<>(messageType, AbstractNioInitiator.this));
            }
        });
        this.bootstrap.option(ChannelOption.TCP_NODELAY, configuration.isTcpNoDelay());
        this.bootstrap.option(ChannelOption.SO_KEEPALIVE, configuration.isSoKeepAlive());
        this.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) configuration.getConnectTimeout());
        this.bootstrap.group(workerGroup != null ? workerGroup : (workerGroup = new NioEventLoopGroup()));
        if (configuration.getSoSendBufferSize() > 0)
            this.bootstrap.option(ChannelOption.SO_SNDBUF, configuration.getSoSendBufferSize());
        if (configuration.getSoRecvBufferSize() > 0)
            this.bootstrap.option(ChannelOption.SO_RCVBUF, configuration.getSoRecvBufferSize());
    }

    protected void reconnect(long delay, CompletableFuture<Void> r, String host, int port) {
        this.bootstrap.config().group().schedule(() -> connect(r, host, port), delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletableFuture<Void> connect(String host, int port) {
        CompletableFuture<Void> r = new ListenableFuture<>();
        connect(r, host, port);
        return r;
    }

    protected void connect(CompletableFuture<Void> r, String host, int port) {
        final ChannelFutureListener v = new ConnectFutureListenerImpl(r, host, port);
        ChannelFuture f = this.bootstrap.connect(host, port);
        f.addListener(v);
    }

    @Override
    public CompletableFuture<?> shutdown() {
        return new ListenableChannelFuture<>(workerGroup.shutdownGracefully());
    }

    private class ConnectFutureListenerImpl implements ChannelFutureListener {
        //
        private final int port;
        private final String host;
        private final long mark = System.nanoTime();
        private final CompletableFuture<Void> future;

        private ConnectFutureListenerImpl(CompletableFuture<Void> future, String host, int port) {
            this.future = future;
            this.host = host;
            this.port = port;
        }

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            final long et = System.nanoTime() - mark;
            if (!f.isSuccess()) {
                if (configuration.isAutoReconnect())
                    reconnect(configuration.getReconnectInterval(), future, host, port);
                else future.failure(f.cause());
            } else {
                future.success(null);
                logger.info("connected to host: " + host + ", port: " + port + ", elapsed time: " + toMillis(et) + " ms");
            }
        }
    }
}
