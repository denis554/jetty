// ========================================================================
// Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.server.AsyncHttpConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Timeout.Task;

/* ------------------------------------------------------------------------------- */
/**
 * Selecting NIO connector.
 * <p>
 * This connector uses efficient NIO buffers with a non blocking threading model. Direct NIO buffers
 * are used and threads are only allocated to connections with requests. Synchronization is used to
 * simulate blocking for the servlet API, and any unflushed content at the end of request handling
 * is written asynchronously.
 * </p>
 * <p>
 * This connector is best used when there are a many connections that have idle periods.
 * </p>
 * <p>
 * When used with {@link org.eclipse.jetty.continuation.Continuation}, threadless waits are supported.
 * If a filter or servlet returns after calling {@link Continuation#suspend()} or when a
 * runtime exception is thrown from a call to {@link Continuation#undispatch()}, Jetty will
 * will not send a response to the client. Instead the thread is released and the Continuation is
 * placed on the timer queue. If the Continuation timeout expires, or it's
 * resume method is called, then the request is again allocated a thread and the request is retried.
 * The limitation of this approach is that request content is not available on the retried request,
 * thus if possible it should be read after the continuation or saved as a request attribute or as the
 * associated object of the Continuation instance.
 * </p>
 *
 * @org.apache.xbean.XBean element="nioConnector" description="Creates an NIO based socket connector"
 */
public class SelectChannelConnector extends AbstractNIOConnector
{
    private static final Logger LOG = Log.getLogger(SelectChannelConnector.class);

    protected ServerSocketChannel _acceptChannel;
    private int _lowResourcesConnections;
    private int _lowResourcesMaxIdleTime;
    private int _localPort=-1;

    private final SelectorManager _manager = new ConnectorSelectorManager();

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     *
     */
    public SelectChannelConnector()
    {
        _manager.setMaxIdleTime(getMaxIdleTime());
        setAcceptors(Math.max(1,(Runtime.getRuntime().availableProcessors()+3)/4));
    }

    /* ------------------------------------------------------------ */
    @Override
    public void accept(int acceptorID) throws IOException
    {
        ServerSocketChannel server = _acceptChannel;
        if (server!=null && server.isOpen())
        {
            SocketChannel channel = _acceptChannel.accept();
            channel.configureBlocking(false);
            Socket socket = channel.socket();
            configure(socket);
            _manager.register(channel);
        }
    }

    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        synchronized(this)
        {
            if (_acceptChannel != null)
                _acceptChannel.close();
            _acceptChannel = null;
            _localPort=-2;
        }
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public void customize(EndPoint endpoint, Request request) throws IOException
    {
        SelectChannelEndPoint cep = ((SelectChannelEndPoint)endpoint);
        cep.cancelIdle();
        request.setTimeStamp(cep.getSelectSet().getNow());
        endpoint.setMaxIdleTime(_maxIdleTime);
        super.customize(endpoint, request);
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public void persist(EndPoint endpoint) throws IOException
    {
        ((SelectChannelEndPoint)endpoint).scheduleIdle();
        super.persist(endpoint);
    }

    /* ------------------------------------------------------------ */
    public Object getConnection()
    {
        return _acceptChannel;
    }

    /* ------------------------------------------------------------------------------- */
    public int getLocalPort()
    {
        synchronized(this)
        {
            return _localPort;
        }
    }

    /* ------------------------------------------------------------ */
    public void open() throws IOException
    {
        synchronized(this)
        {
            if (_acceptChannel == null)
            {
                // Create a new server socket
                _acceptChannel = ServerSocketChannel.open();
                // Set to blocking mode
                _acceptChannel.configureBlocking(true);

                // Bind the server socket to the local host and port
                _acceptChannel.socket().setReuseAddress(getReuseAddress());
                InetSocketAddress addr = getHost()==null?new InetSocketAddress(getPort()):new InetSocketAddress(getHost(),getPort());
                _acceptChannel.socket().bind(addr,getAcceptQueueSize());

                _localPort=_acceptChannel.socket().getLocalPort();
                if (_localPort<=0)
                    throw new IOException("Server channel not bound");

            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxIdleTime(int maxIdleTime)
    {
        _manager.setMaxIdleTime(maxIdleTime);
        super.setMaxIdleTime(maxIdleTime);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the lowResourcesConnections
     */
    public int getLowResourcesConnections()
    {
        return _lowResourcesConnections;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the number of connections, which if exceeded places this manager in low resources state.
     * This is not an exact measure as the connection count is averaged over the select sets.
     * @param lowResourcesConnections the number of connections
     * @see #setLowResourcesMaxIdleTime(int)
     */
    public void setLowResourcesConnections(int lowResourcesConnections)
    {
        _lowResourcesConnections=lowResourcesConnections;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the lowResourcesMaxIdleTime
     */
    @Override
    public int getLowResourcesMaxIdleTime()
    {
        return _lowResourcesMaxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the period in ms that a connection is allowed to be idle when this there are more
     * than {@link #getLowResourcesConnections()} connections.  This allows the server to rapidly close idle connections
     * in order to gracefully handle high load situations.
     * @param lowResourcesMaxIdleTime the period in ms that a connection is allowed to be idle when resources are low.
     * @see #setMaxIdleTime(int)
     */
    @Override
    public void setLowResourcesMaxIdleTime(int lowResourcesMaxIdleTime)
    {
        _lowResourcesMaxIdleTime=lowResourcesMaxIdleTime;
        super.setLowResourcesMaxIdleTime(lowResourcesMaxIdleTime);
    }


    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.AbstractConnector#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _manager.setSelectSets(getAcceptors());
        _manager.setMaxIdleTime(getMaxIdleTime());
        _manager.setLowResourcesConnections(getLowResourcesConnections());
        _manager.setLowResourcesMaxIdleTime(getLowResourcesMaxIdleTime());
        _manager.start();

        super.doStart();

        // start a thread to Select
        for (int i=0;i<getAcceptors();i++)
        {
            final int id=i;
            _manager.dispatch(new Runnable()
            {
                public void run()
                {
                    String name=Thread.currentThread().getName();
                    try
                    {
                        Thread.currentThread().setName(name+" Selector"+id+" "+SelectChannelConnector.this);
                        while (isRunning())
                        {
                            try
                            {
                                _manager.doSelect(id);
                            }
                            catch(ThreadDeath e)
                            {
                                throw e;
                            }
                            catch(IOException e)
                            {
                                LOG.ignore(e);
                            }
                            catch(Exception e)
                            {
                                LOG.warn(e);
                            }
                        }
                    }
                    finally
                    {
                        Thread.currentThread().setName(name);
                    }
                }
            });
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.AbstractConnector#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        synchronized(this)
        {
            if(_manager.isRunning())
            {
                try
                {
                    _manager.stop();
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
    {
        return new SelectChannelEndPoint(channel,selectSet,key, SelectChannelConnector.this._maxIdleTime);
    }

    /* ------------------------------------------------------------------------------- */
    protected void endPointClosed(SelectChannelEndPoint endpoint)
    {
        connectionClosed(endpoint.getConnection());
    }

    /* ------------------------------------------------------------------------------- */
    protected Connection newConnection(SocketChannel channel,final SelectChannelEndPoint endpoint)
    {
        return new SelectChannelHttpConnection(SelectChannelConnector.this,endpoint,getServer(),endpoint);
    }

    /* ------------------------------------------------------------ */
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append(String.valueOf(this)).append("\n");
        ServerSocketChannel channel=_acceptChannel;
        if (channel==null)
            AggregateLifeCycle.dump(out,indent,Arrays.asList(new Object[]{null,"CLOSED",_manager}));
        else
            AggregateLifeCycle.dump(out,indent,Arrays.asList(new Object[]{_acceptChannel,_acceptChannel.isOpen()?"OPEN":"CLOSED",_manager}));
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class SelectChannelHttpConnection extends AsyncHttpConnection
    {
        private final SelectChannelEndPoint _endpoint;

        private SelectChannelHttpConnection(Connector connector, EndPoint endpoint, Server server, SelectChannelEndPoint endpoint2)
        {
            super(connector,endpoint,server);
            _endpoint = endpoint2;
        }

        /* ------------------------------------------------------------ */
        @Override
        public void cancelTimeout(Task task)
        {
            _endpoint.getSelectSet().cancelTimeout(task);
        }

        /* ------------------------------------------------------------ */
        @Override
        public void scheduleTimeout(Task task, long timeoutMs)
        {
            _endpoint.getSelectSet().scheduleTimeout(task,timeoutMs);
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final class ConnectorSelectorManager extends SelectorManager
    {
        @Override
        public boolean dispatch(Runnable task)
        {
            return getThreadPool().dispatch(task);
        }

        @Override
        protected void endPointClosed(final SelectChannelEndPoint endpoint)
        {
            SelectChannelConnector.this.endPointClosed(endpoint);
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
            // TODO handle max connections and low resources
            connectionOpened(endpoint.getConnection());
        }

        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
            connectionUpgraded(oldConnection,endpoint.getConnection());
        }

        @Override
        protected Connection newConnection(SocketChannel channel,SelectChannelEndPoint endpoint)
        {
            return SelectChannelConnector.this.newConnection(channel,endpoint);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey sKey) throws IOException
        {
            return SelectChannelConnector.this.newEndPoint(channel,selectSet,sKey);
        }
    }

}
