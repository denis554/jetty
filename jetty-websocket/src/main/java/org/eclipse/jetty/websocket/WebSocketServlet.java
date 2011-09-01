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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/* ------------------------------------------------------------ */
/**
 * Servlet to upgrade connections to WebSocket
 * <p>
 * The request must have the correct upgrade headers, else it is
 * handled as a normal servlet request.
 * <p>
 * The initParameter "bufferSize" can be used to set the buffer size,
 * which is also the max frame byte size (default 8192).
 * <p>
 * The initParameter "maxIdleTime" can be used to set the time in ms
 * that a websocket may be idle before closing.
 * <p>
 * The initParameter "maxTextMessagesSize" can be used to set the size in characters
 * that a websocket may be accept before closing.
 * <p>
 * The initParameter "maxBinaryMessagesSize" can be used to set the size in bytes
 * that a websocket may be accept before closing.
 * 
 */
public abstract class WebSocketServlet extends HttpServlet implements WebSocketFactory.Acceptor
{
    WebSocketFactory _webSocketFactory;
       
    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.GenericServlet#init()
     */
    @Override
    public void init() throws ServletException
    {
        String bs=getInitParameter("bufferSize");
        _webSocketFactory = new WebSocketFactory(this,bs==null?8192:Integer.parseInt(bs));
        String max=getInitParameter("maxIdleTime");
        if (max!=null)
            _webSocketFactory.setMaxIdleTime(Integer.parseInt(max));
        
        max=getInitParameter("maxTextMessageSize");
        if (max!=null)
            _webSocketFactory.setMaxTextMessageSize(Integer.parseInt(max));
        
        max=getInitParameter("maxBinaryMessageSize");
        if (max!=null)
            _webSocketFactory.setMaxBinaryMessageSize(Integer.parseInt(max));
        
    }

    /* ------------------------------------------------------------ */
    /**
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {   
        if (_webSocketFactory.acceptWebSocket(request,response) || response.isCommitted())
            return;
        super.service(request,response);
    }

    /* ------------------------------------------------------------ */
    public boolean checkOrigin(HttpServletRequest request, String origin)
    {
        return true;
    }
    
    
    
}
