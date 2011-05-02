// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.websocket;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.util.log.Log;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketFactory
{
    public interface Acceptor
    {
        WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);

        String checkOrigin(HttpServletRequest request, String host, String origin);
    }

    private final Acceptor _acceptor;
    private WebSocketBuffers _buffers;
    private int _maxIdleTime = 300000;

    public WebSocketFactory(Acceptor acceptor)
    {
        this(acceptor, 64 * 1024);
    }

    public WebSocketFactory(Acceptor acceptor, int bufferSize)
    {
        _buffers = new WebSocketBuffers(bufferSize);
        _acceptor = acceptor;
    }

    /**
     * Get the maxIdleTime.
     *
     * @return the maxIdleTime
     */
    public long getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /**
     * Set the maxIdleTime.
     *
     * @param maxIdleTime the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime)
    {
        _maxIdleTime = maxIdleTime;
    }

    /**
     * Get the bufferSize.
     *
     * @return the bufferSize
     */
    public int getBufferSize()
    {
        return _buffers.getBufferSize();
    }

    /**
     * Set the bufferSize.
     *
     * @param bufferSize the bufferSize to set
     */
    public void setBufferSize(int bufferSize)
    {
        if (bufferSize != getBufferSize())
            _buffers = new WebSocketBuffers(bufferSize);
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>This method will not normally return, but will instead throw a
     * UpgradeConnectionException, to exit HTTP handling and initiate
     * WebSocket handling of the connection.
     *
     * @param request   The request to upgrade
     * @param response  The response to upgrade
     * @param websocket The websocket handler implementation to use
     * @param origin    The origin of the websocket connection
     * @param protocol  The websocket protocol
     * @throws IOException in case of I/O errors
     */
    public void upgrade(HttpServletRequest request, HttpServletResponse response, WebSocket websocket, String origin, String protocol)
            throws IOException
    {
        if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
            throw new IllegalStateException("!Upgrade:websocket");
        if (!"HTTP/1.1".equals(request.getProtocol()))
            throw new IllegalStateException("!HTTP/1.1");

        int draft = request.getIntHeader("Sec-WebSocket-Version");
        if (draft < 0)
            draft = request.getIntHeader("Sec-WebSocket-Draft");
        HttpConnection http = HttpConnection.getCurrentConnection();
        ConnectedEndPoint endp = (ConnectedEndPoint)http.getEndPoint();

        final WebSocketConnection connection;
        switch (draft)
        {
            case -1:
            case 0:
                connection = new WebSocketConnectionD00(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol);
                break;
            case 6:
                connection = new WebSocketConnectionD06(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol);
                break;
            case 7:
                connection = new WebSocketConnectionD07(websocket, endp, _buffers, http.getTimeStamp(), _maxIdleTime, protocol,null);
                break;
            default:
                Log.warn("Unsupported Websocket version: "+draft);
                throw new HttpException(400, "Unsupported draft specification: " + draft);
        }

        // Let the connection finish processing the handshake
        connection.handshake(request, response, origin, protocol);
        response.flushBuffer();

        // Give the connection any unused data from the HTTP connection.
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getHeaderBuffer());
        connection.fillBuffersFrom(((HttpParser)http.getParser()).getBodyBuffer());

        // Tell jetty about the new connection
        request.setAttribute("org.eclipse.jetty.io.Connection", connection);
    }

    public static String[] parseProtocols(String protocol)
    {
        if (protocol == null)
            return new String[]{null};
        protocol = protocol.trim();
        if (protocol == null || protocol.length() == 0)
            return new String[]{null};
        String[] passed = protocol.split("\\s*,\\s*");
        String[] protocols = new String[passed.length + 1];
        System.arraycopy(passed, 0, protocols, 0, passed.length);
        return protocols;
    }

    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade")))
        {
            String protocol = request.getHeader("Sec-WebSocket-Protocol");
            if (protocol == null) // TODO remove once draft period is over
                protocol = request.getHeader("WebSocket-Protocol");

            WebSocket websocket = null;
            for (String p : WebSocketFactory.parseProtocols(protocol))
            {
                websocket = _acceptor.doWebSocketConnect(request, p);
                if (websocket != null)
                {
                    protocol = p;
                    break;
                }
            }

            String host = request.getHeader("Host");
            String origin = request.getHeader("Origin");
            origin = _acceptor.checkOrigin(request, host, origin);

            if (websocket != null)
            {
                upgrade(request, response, websocket, origin, protocol);
                return true;
            }

            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }

        return false;
    }
}
