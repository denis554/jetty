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

package org.eclipse.jetty.io.nio;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * An Endpoint that can be scheduled by {@link SelectorManager}.
 */
public class SelectChannelEndPoint extends ChannelEndPoint implements AsyncEndPoint, ConnectedEndPoint
{
    public static final Logger __log=Log.getLogger("org.eclipse.jetty.io.nio");
    
    private final SelectorManager.SelectSet _selectSet;
    private final SelectorManager _manager;
    private final Runnable _handler = new Runnable()
        {
            public void run() { handle(); }
        };

    private volatile Connection _connection;
    private boolean _dispatched = false;
    private boolean _redispatched = false;
    private volatile boolean _writable = true;

    private  SelectionKey _key;
    private int _interestOps;
    private boolean _readBlocked;
    private boolean _writeBlocked;
    private boolean _open;
    private volatile long _idleTimestamp;

    /* ------------------------------------------------------------ */
    public SelectChannelEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key, int maxIdleTime)
        throws IOException
    {
        super(channel, maxIdleTime);

        _manager = selectSet.getManager();
        _selectSet = selectSet;
        _dispatched = false;
        _redispatched = false;
        _open=true;
        _key = key;

        _connection = _manager.newConnection(channel,this);

        scheduleIdle();
    }

    /* ------------------------------------------------------------ */
    public SelectChannelEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key)
        throws IOException
    {
        super(channel);

        _manager = selectSet.getManager();
        _selectSet = selectSet;
        _dispatched = false;
        _redispatched = false;
        _open=true;
        _key = key;

        _connection = _manager.newConnection(channel,this);

        scheduleIdle();
    }
    
    /* ------------------------------------------------------------ */
    public SelectionKey getSelectionKey()
    {
        synchronized (this)
        {
            return _key;
        }
    }

    /* ------------------------------------------------------------ */
    public SelectorManager getSelectManager()
    {
        return _manager;
    }

    /* ------------------------------------------------------------ */
    public Connection getConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    public void setConnection(Connection connection)
    {
        Connection old=_connection;
        _connection=connection;
        _manager.endPointUpgraded(this,old);
    }

    /* ------------------------------------------------------------ */
    public long getIdleTimestamp()
    {
        return _idleTimestamp;
    }
    
    /* ------------------------------------------------------------ */
    /** Called by selectSet to schedule handling
     *
     */
    public void schedule()
    {
        synchronized (this)
        {
            // If there is no key, then do nothing
            if (_key == null || !_key.isValid())
            {
                _readBlocked=false;
                _writeBlocked=false;
                this.notifyAll();
                return;
            }

            // If there are threads dispatched reading and writing
            if (_readBlocked || _writeBlocked)
            {
                // assert _dispatched;
                if (_readBlocked && _key.isReadable())
                    _readBlocked=false;
                if (_writeBlocked && _key.isWritable())
                    _writeBlocked=false;

                // wake them up is as good as a dispatched.
                this.notifyAll();

                // we are not interested in further selecting
                if (_dispatched)
                    _key.interestOps(0);
                return;
            }

            // Otherwise if we are still dispatched
            if (!isReadyForDispatch())
            {
                // we are not interested in further selecting
                _key.interestOps(0);
                return;
            }

            // Remove writeable op
            if ((_key.readyOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE && (_key.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE)
            {
                // Remove writeable op
                _interestOps = _key.interestOps() & ~SelectionKey.OP_WRITE;
                _key.interestOps(_interestOps);
                _writable = true; // Once writable is in ops, only removed with dispatch.
            }

            // Dispatch if we are not already
            if (!_dispatched)
            {
                dispatch();
                if (_dispatched && !_selectSet.getManager().isDeferringInterestedOps0())
                {
                    _key.interestOps(0);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void dispatch()
    {
        synchronized(this)
        {
            if (_dispatched)
            {
                _redispatched=true;
            }
            else
            {
                _dispatched = true;
                boolean dispatched = _manager.dispatch(_handler);
                if(!dispatched)
                {
                    _dispatched = false;
                    __log.warn("Dispatched Failed! "+this+" to "+_manager);
                    updateKey();
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Called when a dispatched thread is no longer handling the endpoint.
     * The selection key operations are updated.
     * @return If false is returned, the endpoint has been redispatched and
     * thread must keep handling the endpoint.
     */
    protected boolean undispatch()
    {
        synchronized (this)
        {
            if (_redispatched)
            {
                _redispatched=false;
                return false;
            }
            _dispatched = false;
            updateKey();
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    public void scheduleIdle()
    {
        _idleTimestamp=System.currentTimeMillis();
    }

    /* ------------------------------------------------------------ */
    public void cancelIdle()
    {
        _idleTimestamp=0;
    }

    /* ------------------------------------------------------------ */
    public void checkIdleTimestamp(long now)
    {
        long idleTimestamp=_idleTimestamp;
        if (idleTimestamp!=0 && _maxIdleTime>0 && now>(idleTimestamp+_maxIdleTime))
            idleExpired();
    }

    /* ------------------------------------------------------------ */
    protected void idleExpired()
    {
        _connection.idleExpired();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the endpoint has produced/consumed bytes itself (non application data).
     */
    public boolean isProgressing()
    {
        return false;
    }
    
    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        int l = super.flush(header, buffer, trailer);
        
        // If there was something to write and it wasn't written, then we are not writable.
        if (l==0 && ( header!=null && header.hasContent() || buffer!=null && buffer.hasContent() || trailer!=null && trailer.hasContent()))
        {
            synchronized (this)
            {
                _writable=false;
                if (!_dispatched)
                    updateKey();
            }
        }
        else
            _writable=true;
        return l;
    }

    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public int flush(Buffer buffer) throws IOException
    {
        int l = super.flush(buffer);
        
        // If there was something to write and it wasn't written, then we are not writable.
        if (l==0 && buffer!=null && buffer.hasContent())
        {
            synchronized (this)
            {
                _writable=false;
                if (!_dispatched)
                    updateKey();
            }
        }
        else
            _writable=true;
        
        return l;
    }

    /* ------------------------------------------------------------ */
    public boolean isReadyForDispatch()
    {
        synchronized (this)
        {
            // Ready if not dispatched and not suspended
            return !(_dispatched || getConnection().isSuspended());
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * Allows thread to block waiting for further events.
     */
    @Override
    public boolean blockReadable(long timeoutMs) throws IOException
    {
        synchronized (this)
        {
            long now=_selectSet.getNow();
            long end=now+timeoutMs;
            try
            {
                _readBlocked=true;
                while (isOpen() && _readBlocked)
                {
                    try
                    {
                        updateKey();
                        this.wait(timeoutMs>=0?(end-now):10000);
                    }
                    catch (InterruptedException e)
                    {
                        __log.warn(e);
                    }
                    finally
                    {
                        now=_selectSet.getNow();
                    }

                    if (_readBlocked && timeoutMs>0 && now>=end)
                        return false;
                }
            }
            finally
            {
                _readBlocked=false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------ */
    /*
     * Allows thread to block waiting for further events.
     */
    @Override
    public boolean blockWritable(long timeoutMs) throws IOException
    {
        synchronized (this)
        {
            if (!isOpen() || isOutputShutdown())
                throw new EofException();
            
            long now=_selectSet.getNow();
            long end=now+timeoutMs;
            try
            {
                _writeBlocked=true;
                while (isOpen() && _writeBlocked && !isOutputShutdown())
                {
                    try
                    {
                        updateKey();
                        this.wait(timeoutMs>=0?(end-now):10000);
                    }
                    catch (InterruptedException e)
                    {
                        __log.warn(e);
                    }
                    finally
                    {
                        now=_selectSet.getNow();
                    }
                    if (_writeBlocked && timeoutMs>0 && now>=end)
                        return false;
                }
            }
            catch(Throwable e)
            {
                // TODO remove this if it finds nothing
                __log.warn(e);
                if (e instanceof RuntimeException)
                    throw (RuntimeException)e;
                if (e instanceof Error)
                    throw (Error)e;
                throw new RuntimeException(e);
            }
            finally
            {
                _writeBlocked=false;
                if (_idleTimestamp!=-1)
                    scheduleIdle();
            }
        }
        return true;
    }
    
    /* ------------------------------------------------------------ */
    public void scheduleWrite()
    {
        _writable=false;
        updateKey();
    }

    /* ------------------------------------------------------------ */
    /**
     * Updates selection key. Adds operations types to the selection key as needed. No operations
     * are removed as this is only done during dispatch. This method records the new key and
     * schedules a call to doUpdateKey to do the keyChange
     */
    private void updateKey()
    {
        synchronized (this)
        {
            int ops=-1;
            if (getChannel().isOpen())
            {
                _interestOps =
                    ((!_socket.isInputShutdown() && (!_dispatched || _readBlocked))  ? SelectionKey.OP_READ  : 0)
                |   ((!_socket.isOutputShutdown()&& (!_writable   || _writeBlocked)) ? SelectionKey.OP_WRITE : 0);
                try
                {
                    ops = ((_key!=null && _key.isValid())?_key.interestOps():-1);
                }
                catch(Exception e)
                {
                    _key=null;
                    __log.ignore(e);
                }
            }

            if(_interestOps == ops && getChannel().isOpen())
                return;
        }
        _selectSet.addChange(this);
        _selectSet.wakeup();
    }

    /* ------------------------------------------------------------ */
    /**
     * Synchronize the interestOps with the actual key. Call is scheduled by a call to updateKey
     */
    void doUpdateKey()
    {
        synchronized (this)
        {
            if (getChannel().isOpen())
            {
                if (_interestOps>0)
                {
                    if (_key==null || !_key.isValid())
                    {
                        SelectableChannel sc = (SelectableChannel)getChannel();
                        if (sc.isRegistered())
                        {
                            updateKey();
                        }
                        else
                        {
                            try
                            {
                                _key=((SelectableChannel)getChannel()).register(_selectSet.getSelector(),_interestOps,this);
                            }
                            catch (Exception e)
                            {
                                __log.ignore(e);
                                if (_key!=null && _key.isValid())
                                {
                                    _key.cancel();
                                }
                                cancelIdle();

                                if (_open)
                                {
                                    _selectSet.destroyEndPoint(this);
                                }
                                _open=false;
                                _key = null;
                            }
                        }
                    }
                    else
                    {
                        _key.interestOps(_interestOps);
                    }
                }
                else
                {
                    if (_key!=null && _key.isValid())
                        _key.interestOps(0);
                    else
                        _key=null;
                }
            }
            else
            {
                if (_key!=null && _key.isValid())
                    _key.cancel();

                cancelIdle();
                if (_open)
                {
                    _selectSet.destroyEndPoint(this);
                }
                _open=false;
                _key = null;
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     */
    protected void handle()
    {
        boolean dispatched=true;
        try
        {
            while(dispatched)
            {
                try
                {
                    while(true)
                    {
                        final Connection next = _connection.handle();
                        if (next!=_connection)
                        {
                            __log.debug("{} replaced {}",next,_connection);
                            _connection=next;
                            continue;
                        }
                        break;
                    }
                }
                catch (ClosedChannelException e)
                {
                    __log.ignore(e);
                }
                catch (EofException e)
                {
                    __log.debug("EOF", e);
                    try{close();}
                    catch(IOException e2){__log.ignore(e2);}
                }
                catch (IOException e)
                {
                    __log.warn(e.toString());
                    __log.debug(e);
                    try{close();}
                    catch(IOException e2){__log.ignore(e2);}
                }
                catch (Throwable e)
                {
                    __log.warn("handle failed", e);
                    try{close();}
                    catch(IOException e2){__log.ignore(e2);}
                }
                dispatched=!undispatch();
            }
        }
        finally
        {
            if (dispatched)
            {
                dispatched=!undispatch();
                while (dispatched)
                {
                    __log.warn("SCEP.run() finally DISPATCHED");
                    dispatched=!undispatch();
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.nio.ChannelEndPoint#close()
     */
    @Override
    public void close() throws IOException
    {
        try
        {
            super.close();
        }
        catch (IOException e)
        {
            __log.ignore(e);
        }
        finally
        {
            updateKey();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        synchronized(this)
        {
            return "SCEP@" + hashCode() + _channel+            
            "[d=" + _dispatched + ",io=" + _interestOps+
            ",w=" + _writable + ",rb=" + _readBlocked + ",wb=" + _writeBlocked + "]";
        }
    }

    /* ------------------------------------------------------------ */
    public SelectSet getSelectSet()
    {
        return _selectSet;
    }

    /* ------------------------------------------------------------ */
    /**
     * Don't set the SoTimeout
     * @see org.eclipse.jetty.io.nio.ChannelEndPoint#setMaxIdleTime(int)
     */
    @Override
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        _maxIdleTime=timeMs;
    }

}
