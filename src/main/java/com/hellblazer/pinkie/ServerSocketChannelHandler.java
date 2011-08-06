/** (C) Copyright 2011 Hal Hildebrand, all rights reserved.

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.hellblazer.pinkie;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 * 
 */
public abstract class ServerSocketChannelHandler extends ChannelHandler {

    private static Logger log = Logger.getLogger(ServerSocketChannelHandler.class.getCanonicalName());

    public static ServerSocketChannel bind(SocketOptions options,
                                           InetSocketAddress endpointAddress)
                                                                             throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        ServerSocket serverSocket = server.socket();
        serverSocket.bind(endpointAddress, options.getBacklog());
        return server;
    }

    public static InetSocketAddress getLocalAddress(ServerSocketChannel channel) {

        return new InetSocketAddress(channel.socket().getInetAddress(),
                                     channel.socket().getLocalPort());
    }

    public ServerSocketChannelHandler(String handlerName,
                                      SelectableChannel channel,
                                      InetSocketAddress endpointAddress,
                                      SocketOptions socketOptions,
                                      ExecutorService commsExec)
                                                                throws IOException {
        super(handlerName, channel, endpointAddress, socketOptions, commsExec);
    }

    public ServerSocketChannelHandler(String handlerName,
                                      ServerSocketChannel channel,
                                      SocketOptions socketOptions,
                                      ExecutorService commsExec)
                                                                throws IOException {
        this(handlerName, channel, getLocalAddress(channel), socketOptions,
             commsExec);
    }

    public ServerSocketChannelHandler(String handlerName,
                                      SocketOptions socketOptions,
                                      InetSocketAddress endpointAddress,
                                      ExecutorService commsExec)
                                                                throws IOException {
        this(handlerName, bind(socketOptions, endpointAddress), socketOptions,
             commsExec);
    }

    /**
     * Connect to the remote address. The connection will be made in a
     * non-blocking fashion, and only after the
     * ChannelHandler.handleConnect(SocketChannel) method has been called is the
     * socket channel actually connected.
     * 
     * @param remoteAddress
     * @return the socket channel handler for the new connection
     * @throws IOException
     */
    public SocketChannelHandler connectTo(InetSocketAddress remoteAddress)
                                                                          throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        options.configure(socketChannel.socket());
        SocketChannelHandler handler = createHandler(socketChannel);
        socketChannel.configureBlocking(false);
        if (socketChannel.connect(remoteAddress)) {
            // Immediate connection handling
            handler.connectHandler().run();
            return handler;
        }
        // Non blocking connection handling
        try {
            register(handler.getChannel(), handler, SelectionKey.OP_CONNECT);
        } catch (CancelledKeyException e) {
            if (log.isLoggable(Level.WARNING)) {
                log.log(Level.WARNING,
                        String.format("Cancelled key for %s", handler), e);
            }
            throw new IllegalStateException(
                                            "Could not register the connect selection key",
                                            e);
        }
        wakeup();
        return handler;
    }

    @Override
    void dispatch(SelectionKey key) throws IOException {
        if (key.isAcceptable()) {
            handleAccept(key);
        } else if (key.isReadable()) {
            handleRead(key);
        } else if (key.isWritable()) {
            handleWrite(key);
        } else if (key.isConnectable()) {
            handleConnect(key);
        } else {
            if (log.isLoggable(Level.WARNING)) {
                log.warning("Unhandled key: " + key);
            }
        }
    }

    void handleConnect(SelectionKey key) {
        if (log.isLoggable(Level.FINEST)) {
            log.finest("Handling read");
        }
        key.cancel();
        try {
            ((SocketChannel) key.channel()).finishConnect();
        } catch (IOException e) {
            log.log(Level.SEVERE, "Unable to finish connection", e);
        }
        if (log.isLoggable(Level.FINE)) {
            log.fine("Dispatching connected action");
        }
        try {
            commsExecutor.execute(((SocketChannelHandler) key.attachment()).connectHandler());
        } catch (RejectedExecutionException e) {
            if (log.isLoggable(Level.FINEST)) {
                log.log(Level.FINEST, "cannot execute connect action", e);
            }
        }
    }
}
