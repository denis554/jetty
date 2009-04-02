// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;

/* ------------------------------------------------------------ */
/** A <code>HandlerContainer</code> that allows a hot swap
 * of a wrapped handler.
 * 
 */
public class HotSwapHandler extends AbstractHandlerContainer
{
    private volatile Handler _handler;

    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public HotSwapHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the handlers.
     */
    public Handler getHandler()
    {
        return _handler;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param handler Set the {@link Handler} which should be wrapped.
     */
    public void setHandler(Handler handler)
    {
        try
        {
            if (isRunning())
                throw new IllegalStateException(RUNNING);

            Handler old_handler = _handler;

            if (getServer()!=null)
                getServer().getContainer().update(this, old_handler, handler, "handler");

            if (handler!=null)
            {
                handler.setServer(getServer());
                if (isStarted())
                    handler.start();
            }

            _handler = handler;
            
            if (isStarted())
                old_handler.stop();

        }
        catch(Error e)
        {
            throw e;
        }
        catch(RuntimeException e)
        {
            throw e;
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    protected void doStart() throws Exception
    {
        if (_handler!=null)
            _handler.start();
        super.doStart();
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_handler!=null)
            _handler.stop();
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.jetty.server.server.EventHandler#handle(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public void handle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_handler!=null && isStarted())
        {
            _handler.handle(target,request, response);
        }
    }
    

    /* ------------------------------------------------------------ */
    public void setServer(Server server)
    {
        if (isRunning())
            throw new IllegalStateException(RUNNING);
            
        Server old_server=getServer();
        
        super.setServer(server);
        
        Handler h=getHandler();
        if (h!=null)
            h.setServer(server);
        
        if (server!=null && server!=old_server)
            server.getContainer().update(this, null,_handler, "handler");
    }
    

    /* ------------------------------------------------------------ */
    protected Object expandChildren(Object list, Class byClass)
    {
        return expandHandler(_handler,list,byClass);
    }

   
}
