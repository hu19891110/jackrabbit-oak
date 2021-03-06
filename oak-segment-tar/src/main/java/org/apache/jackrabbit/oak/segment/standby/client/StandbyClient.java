/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.oak.segment.standby.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.compression.SnappyFramedDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.CharsetUtil;
import org.apache.jackrabbit.oak.segment.standby.codec.GetBlobRequest;
import org.apache.jackrabbit.oak.segment.standby.codec.GetBlobRequestEncoder;
import org.apache.jackrabbit.oak.segment.standby.codec.GetBlobResponse;
import org.apache.jackrabbit.oak.segment.standby.codec.GetHeadRequest;
import org.apache.jackrabbit.oak.segment.standby.codec.GetHeadRequestEncoder;
import org.apache.jackrabbit.oak.segment.standby.codec.GetHeadResponse;
import org.apache.jackrabbit.oak.segment.standby.codec.GetSegmentRequest;
import org.apache.jackrabbit.oak.segment.standby.codec.GetSegmentRequestEncoder;
import org.apache.jackrabbit.oak.segment.standby.codec.GetSegmentResponse;
import org.apache.jackrabbit.oak.segment.standby.codec.ResponseDecoder;

class StandbyClient implements AutoCloseable {

    private final BlockingQueue<GetHeadResponse> headQueue = new LinkedBlockingDeque<>();

    private final BlockingQueue<GetSegmentResponse> segmentQueue = new LinkedBlockingDeque<>();

    private final BlockingQueue<GetBlobResponse> blobQueue = new LinkedBlockingDeque<>();

    private final boolean secure;

    private final int readTimeoutMs;

    private String clientId;

    private NioEventLoopGroup group;

    private Channel channel;

    StandbyClient(String clientId, boolean secure, int readTimeoutMs) {
        this.clientId = clientId;
        this.secure = secure;
        this.readTimeoutMs = readTimeoutMs;
    }

    void connect(String host, int port) throws Exception {
        group = new NioEventLoopGroup();

        final SslContext sslContext;

        if (secure) {
            sslContext = SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
        } else {
            sslContext = null;
        }

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, readTimeoutMs)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();

                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc()));
                        }

                        p.addLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS));

                        // Decoders

                        p.addLast(new SnappyFramedDecoder(true));

                        // Such a big max frame length is needed because blob
                        // values are sent in one big message. In future
                        // versions of the protocol, sending binaries in chunks
                        // should be considered instead.

                        p.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4));
                        p.addLast(new ResponseDecoder());

                        // Encoders

                        p.addLast(new StringEncoder(CharsetUtil.UTF_8));
                        p.addLast(new GetHeadRequestEncoder());
                        p.addLast(new GetSegmentRequestEncoder());
                        p.addLast(new GetBlobRequestEncoder());

                        // Handlers

                        p.addLast(new GetHeadResponseHandler(headQueue));
                        p.addLast(new GetSegmentResponseHandler(segmentQueue));
                        p.addLast(new GetBlobResponseHandler(blobQueue));
                    }

                });

        channel = b.connect(host, port).sync().channel();
    }

    @Override
    public void close() throws InterruptedException {
        channel.close().sync();
        channel = null;

        group.shutdownGracefully();
        group = null;
    }

    String getHead() throws InterruptedException {
        channel.writeAndFlush(new GetHeadRequest(clientId));

        GetHeadResponse response = headQueue.poll(readTimeoutMs, TimeUnit.MILLISECONDS);

        if (response == null) {
            return null;
        }

        return response.getHeadRecordId();
    }

    byte[] getSegment(String segmentId) throws InterruptedException {
        channel.writeAndFlush(new GetSegmentRequest(clientId, segmentId));

        GetSegmentResponse response = segmentQueue.poll(readTimeoutMs, TimeUnit.MILLISECONDS);

        if (response == null) {
            return null;
        }

        return response.getSegmentData();
    }

    byte[] getBlob(String blobId) throws InterruptedException {
        channel.writeAndFlush(new GetBlobRequest(clientId, blobId));

        GetBlobResponse response = blobQueue.poll(readTimeoutMs, TimeUnit.MILLISECONDS);

        if (response == null) {
            return null;
        }

        return response.getBlobData();
    }

}
