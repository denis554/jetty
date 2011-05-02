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
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.websocket.WebSocket.OnFrame;
import org.eclipse.jetty.websocket.WebSocket.OnTextMessage;
import org.eclipse.jetty.websocket.WebSocket.OnBinaryMessage;
import org.eclipse.jetty.websocket.WebSocket.OnControl;

public class WebSocketConnectionD07 extends AbstractConnection implements WebSocketConnection
{
    final static byte OP_CONTINUATION = 0x00;
    final static byte OP_TEXT = 0x01;
    final static byte OP_BINARY = 0x02;
    final static byte OP_EXT_DATA = 0x03;
    
    final static byte OP_CLOSE = 0x08;
    final static byte OP_PING = 0x09;
    final static byte OP_PONG = 0x0A;
    final static byte OP_EXT_CTRL = 0x0B;
    
    final static int CLOSE_NORMAL=1000;
    final static int CLOSE_SHUTDOWN=1001;
    final static int CLOSE_PROTOCOL=1002;
    final static int CLOSE_BADDATA=1003;
    final static int CLOSE_LARGE=1004;
    
    static boolean isLastFrame(byte flags)
    {
        return (flags&0x8)!=0;
    }
    
    static boolean isControlFrame(byte opcode)
    {
        return (opcode&0x8)!=0;
    }
    
    private final static byte[] MAGIC;
    private final IdleCheck _idle;
    private final WebSocketParserD07 _parser;
    private final WebSocketParser.FrameHandler _inbound;
    private final WebSocketGeneratorD07 _generator;
    private final WebSocketGenerator _outbound;
    private final WebSocket _webSocket;
    private final OnFrame _onFrame;
    private final OnBinaryMessage _onBinaryMessage;
    private final OnTextMessage _onTextMessage;
    private final OnControl _onControl;
    private final String _protocol;
    private boolean _closedIn;
    private boolean _closedOut;
    private int _maxTextMessageSize;
    private int _maxBinaryMessageSize=-1;

    static
    {
        try
        {
            MAGIC="258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StringUtil.__ISO_8859_1);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    private final WebSocketParser.FrameHandler _frameHandler= new FrameHandlerD07();

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private final WebSocket.FrameConnection _connection = new FrameConnectionD07();
    

    /* ------------------------------------------------------------ */
    public WebSocketConnectionD07(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol, Extension[] extensions)
        throws IOException
    {
        super(endpoint,timestamp);
        
        // TODO - can we use the endpoint idle mechanism?
        if (endpoint instanceof AsyncEndPoint)
            ((AsyncEndPoint)endpoint).cancelIdle();
        
        _endp.setMaxIdleTime(maxIdleTime);
        
        _webSocket = websocket;
        _onFrame=_webSocket instanceof OnFrame ? (OnFrame)_webSocket : null;
        _onTextMessage=_webSocket instanceof OnTextMessage ? (OnTextMessage)_webSocket : null;
        _onBinaryMessage=_webSocket instanceof OnBinaryMessage ? (OnBinaryMessage)_webSocket : null;
        _onControl=_webSocket instanceof OnControl ? (OnControl)_webSocket : null;
        _generator = new WebSocketGeneratorD07(buffers, _endp,null);
        
        if (extensions!=null)
        {
            byte data_op=OP_EXT_DATA;
            byte ctrl_op=OP_EXT_CTRL;
            byte flag_mask=0x4;
            for (int e=0;e<extensions.length;e++)
            {
                byte[] data_ops=new byte[extensions[e].getDataOpcodes()];
                for (int i=0;i<data_ops.length;i++)
                    data_ops[i]=data_op++;
                byte[] ctrl_ops=new byte[extensions[e].getControlOpcodes()];
                for (int i=0;i<ctrl_ops.length;i++)
                    ctrl_ops[i]=ctrl_op++;
                byte[] flag_masks=new byte[extensions[e].getReservedBits()];
                for (int i=0;i<flag_masks.length;i++)
                {
                    flag_masks[i]=flag_mask;
                    flag_mask= (byte)(flag_mask>>1);
                }
                
                extensions[e].init(
                        e==extensions.length-1?_frameHandler:extensions[e+1],
                                e==0?_generator:extensions[e-1],
                                        data_ops,ctrl_ops,flag_masks);
            }
        }

        _outbound=(extensions==null || extensions.length==0)?_generator:extensions[extensions.length-1];
        _inbound=(extensions==null || extensions.length==0)?_frameHandler:extensions[0];
        
        _parser = new WebSocketParserD07(buffers, endpoint,_inbound,true);
        
        _protocol=protocol;

        // TODO should these be AsyncEndPoint checks/calls?
        if (_endp instanceof SelectChannelEndPoint)
        {
            final SelectChannelEndPoint scep=(SelectChannelEndPoint)_endp;
            scep.cancelIdle();
            _idle=new IdleCheck()
            {
                public void access(EndPoint endp)
                {
                    scep.scheduleIdle();
                }
            };
            scep.scheduleIdle();
        }
        else
        {
            _idle = new IdleCheck()
            {
                public void access(EndPoint endp)
                {}
            };
        }
        
        _maxTextMessageSize=buffers.getBufferSize(); 
        _maxBinaryMessageSize=-1;
    }

    /* ------------------------------------------------------------ */
    public WebSocket.Connection getConnection()
    {
        return _connection;
    }
    
    /* ------------------------------------------------------------ */
    public Connection handle() throws IOException
    {
        try
        {
            // handle the framing protocol
            boolean progress=true;

            while (progress)
            {
                int flushed=_generator.flushBuffer();
                int filled=_parser.parseNext();

                progress = flushed>0 || filled>0;
                
                if (filled<0 || flushed<0)
                {
                    _endp.close();
                    break;
                }
            }
        }
        catch(IOException e)
        {
            try
            {
                _endp.close();
            }
            catch(IOException e2)
            {
                Log.ignore(e2);
            }
            throw e;
        }
        finally
        {
            if (_endp.isOpen())
            {
                _generator.idle();
                _idle.access(_endp);
                if (_closedIn && _closedOut && _outbound.isBufferEmpty())
                    _endp.close();
                else if (_endp.isInputShutdown() && !_closedIn)
                    closeIn(CLOSE_PROTOCOL,null);
                else
                    checkWriteable();
            }
           
        }
        return this;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _parser.isBufferEmpty() && _outbound.isBufferEmpty();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void idleExpired()
    {
        closeOut(WebSocketConnectionD07.CLOSE_NORMAL,"Idle");
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspended()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public void closed()
    {
        _webSocket.onClose(WebSocketConnectionD07.CLOSE_NORMAL,"");
    }

    /* ------------------------------------------------------------ */
    public synchronized void closeIn(int code,String message)
    {
        Log.debug("ClosedIn {} {}",this,message);
        try
        {
            if (_closedOut)
                _endp.close();
            else 
                closeOut(code,message);
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        finally
        {
            _closedIn=true;
        }
    }

    /* ------------------------------------------------------------ */
    public synchronized void closeOut(int code,String message)
    {
        Log.debug("ClosedOut {} {}",this,message);
        try
        {
            if (_closedIn || _closedOut)
                _endp.close();
            else 
            {
                if (code<=0)
                    code=WebSocketConnectionD07.CLOSE_NORMAL;
                byte[] bytes = ("xx"+(message==null?"":message)).getBytes(StringUtil.__ISO_8859_1);
                bytes[0]=(byte)(code/0x100);
                bytes[1]=(byte)(code%0x100);
                _outbound.addFrame((byte)0x8,WebSocketConnectionD07.OP_CLOSE,bytes,0,bytes.length);
            }
            _outbound.flush();
            
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        finally
        {
            _closedOut=true;
        }
    }

    /* ------------------------------------------------------------ */
    public void fillBuffersFrom(Buffer buffer)
    {
        _parser.fill(buffer);
    }

    /* ------------------------------------------------------------ */
    private void checkWriteable()
    {
        if (!_outbound.isBufferEmpty() && _endp instanceof AsyncEndPoint)
        {
            ((AsyncEndPoint)_endp).scheduleWrite();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class FrameConnectionD07 implements WebSocket.FrameConnection
    {
        volatile boolean _disconnecting;
        int _maxTextMessage=WebSocketConnectionD07.this._maxTextMessageSize;
        int _maxBinaryMessage=WebSocketConnectionD07.this._maxBinaryMessageSize;

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendMessage(byte, java.lang.String)
         */
        public synchronized void sendMessage(String content) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            byte[] data = content.getBytes(StringUtil.__UTF8);
            _outbound.addFrame((byte)0x8,WebSocketConnectionD07.OP_TEXT,data,0,data.length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendMessage(byte, byte[], int, int)
         */
        public synchronized void sendMessage(byte[] content, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            _outbound.addFrame((byte)0x8,WebSocketConnectionD07.OP_BINARY,content,offset,length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendFrame(boolean, byte, byte[], int, int)
         */
        public void sendFrame(byte flags,byte opcode, byte[] content, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            _outbound.addFrame(flags,opcode,content,offset,length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        public void sendControl(byte ctrl, byte[] data, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            _outbound.addFrame((byte)0x8,ctrl,data,offset,length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        public boolean isMessageComplete(byte flags)
        {
            return isLastFrame(flags);
        }

        /* ------------------------------------------------------------ */
        public boolean isOpen()
        {
            return _endp!=null&&_endp.isOpen();
        }

        /* ------------------------------------------------------------ */
        public void close(int code, String message)
        {
            if (_disconnecting)
                return;
            _disconnecting=true;
            WebSocketConnectionD07.this.closeOut(code,message);
        }

        /* ------------------------------------------------------------ */
        public void setMaxTextMessageSize(int size)
        {
            _maxTextMessage=size;
        }

        /* ------------------------------------------------------------ */
        public void setMaxBinaryMessageSize(int size)
        {
            _maxBinaryMessage=size;
        }

        /* ------------------------------------------------------------ */
        public int getMaxTextMessageSize()
        {
            return _maxTextMessage;
        }

        /* ------------------------------------------------------------ */
        public int getMaxBinaryMessageSize()
        {
            return _maxBinaryMessage;
        }

        /* ------------------------------------------------------------ */
        public String getProtocol()
        {
            return _protocol;
        }

        /* ------------------------------------------------------------ */
        public byte binaryOpcode()
        {
            return OP_BINARY;
        }

        /* ------------------------------------------------------------ */
        public byte textOpcode()
        {
            return OP_TEXT;
        }

        /* ------------------------------------------------------------ */
        public boolean isControl(byte opcode)
        {
            return isControlFrame(opcode);
        }

        /* ------------------------------------------------------------ */
        public boolean isText(byte opcode)
        {
            return opcode==OP_TEXT;
        }

        /* ------------------------------------------------------------ */
        public boolean isBinary(byte opcode)
        {
            return opcode==OP_BINARY;
        }

        /* ------------------------------------------------------------ */
        public boolean isContinuation(byte opcode)
        {
            return opcode==OP_CONTINUATION;
        }

        /* ------------------------------------------------------------ */
        public boolean isClose(byte opcode)
        {
            return opcode==OP_CLOSE;
        }

        /* ------------------------------------------------------------ */
        public boolean isPing(byte opcode)
        {
            return opcode==OP_PING;
        }

        /* ------------------------------------------------------------ */
        public boolean isPong(byte opcode)
        {
            return opcode==OP_PONG;
        }

        /* ------------------------------------------------------------ */
        public void disconnect()
        {
            close(CLOSE_NORMAL,null);
        }

        /* ------------------------------------------------------------ */
        public String toString()
        {
            return this.getClass().getSimpleName()+"@"+_endp.getLocalAddr()+":"+_endp.getLocalPort()+"<->"+_endp.getRemoteAddr()+":"+_endp.getRemotePort();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class FrameHandlerD07 implements WebSocketParser.FrameHandler
    {
        private final Utf8StringBuilder _utf8 = new Utf8StringBuilder();
        private ByteArrayBuffer _aggregate;
        private byte _opcode=-1;

        public void onFrame(byte flags, byte opcode, Buffer buffer)
        {
            boolean lastFrame = isLastFrame(flags); 
            
            synchronized(WebSocketConnectionD07.this)
            {
                // Ignore incoming after a close
                if (_closedIn)
                    return;
                
                try
                {
                    byte[] array=buffer.array();

                    // Deliver frame if websocket is a FrameWebSocket
                    if (_onFrame!=null)
                    {
                        if (_onFrame.onFrame(flags,opcode,array,buffer.getIndex(),buffer.length()))
                            return;
                    }
                    
                    if (_onControl!=null && isControlFrame(opcode))
                    {
                        if (_onControl.onControl(opcode,array,buffer.getIndex(),buffer.length()))
                            return;
                    }
                    
                    switch(opcode)
                    {
                        case WebSocketConnectionD07.OP_CONTINUATION:
                        {
                            // If text, append to the message buffer
                            if (_opcode==WebSocketConnectionD07.OP_TEXT && _connection.getMaxTextMessageSize()>=0)
                            {
                                if (_utf8.append(buffer.array(),buffer.getIndex(),buffer.length(),_connection.getMaxTextMessageSize()))
                                {
                                    // If this is the last fragment, deliver the text buffer
                                    if (lastFrame && _onTextMessage!=null)
                                    {
                                        _opcode=-1;
                                        String msg =_utf8.toString();
                                        _utf8.reset();
                                        _onTextMessage.onMessage(msg);
                                    }
                                }
                                else
                                {
                                    _connection.close(WebSocketConnectionD07.CLOSE_LARGE,"Text message size > "+_connection.getMaxTextMessageSize()+" chars");
                                    _utf8.reset();
                                    _opcode=-1;
                                }    
                            }
                            else if (_opcode>=0 && _connection.getMaxBinaryMessageSize()>=0)
                            {
                                if (_aggregate.space()<_aggregate.length())
                                {
                                    _connection.close(WebSocketConnectionD07.CLOSE_LARGE,"Message size > "+_connection.getMaxBinaryMessageSize());
                                    _aggregate.clear();
                                    _opcode=-1;
                                }
                                else
                                {
                                    _aggregate.put(buffer);

                                    // If this is the last fragment, deliver
                                    if (lastFrame && _onBinaryMessage!=null)
                                    {
                                        try
                                        {
                                            _onBinaryMessage.onMessage(_aggregate.array(),_aggregate.getIndex(),_aggregate.length());
                                        }
                                        finally
                                        {
                                            _opcode=-1;
                                            _aggregate.clear();
                                        }
                                    }
                                }
                            }
                            break;
                        }
                        case WebSocketConnectionD07.OP_PING:
                        {
                            Log.debug("PING {}",this);
                            if (!_closedOut)
                                _connection.sendControl(WebSocketConnectionD07.OP_PONG,buffer.array(),buffer.getIndex(),buffer.length());
                            break;
                        }

                        case WebSocketConnectionD07.OP_PONG:
                        {
                            Log.debug("PONG {}",this);
                            break;
                        }

                        case WebSocketConnectionD07.OP_CLOSE:
                        {
                            int code=-1;
                            String message=null;
                            if (buffer.length()>=2)
                            {
                                code=buffer.array()[buffer.getIndex()]*0xff+buffer.array()[buffer.getIndex()+1];
                                if (buffer.length()>2)
                                    message=new String(buffer.array(),buffer.getIndex()+2,buffer.length()-2,StringUtil.__UTF8);
                            }
                            closeIn(code,message);
                            break;
                        }


                        case WebSocketConnectionD07.OP_TEXT:
                        {
                            if(_onTextMessage!=null)
                            {
                                if (lastFrame)
                                {
                                    // Deliver the message
                                    _onTextMessage.onMessage(buffer.toString(StringUtil.__UTF8));
                                }
                                else 
                                {
                                    if (_connection.getMaxTextMessageSize()>=0)
                                    {
                                        // If this is a text fragment, append to buffer
                                        if (_utf8.append(buffer.array(),buffer.getIndex(),buffer.length(),_connection.getMaxTextMessageSize()))
                                            _opcode=WebSocketConnectionD07.OP_TEXT;
                                        else
                                        {
                                            _utf8.reset();
                                            _opcode=-1;                                    
                                            _connection.close(WebSocketConnectionD07.CLOSE_LARGE,"Text message size > "+_connection.getMaxTextMessageSize()+" chars");
                                        }
                                    }
                                }
                            }
                            break;
                        }

                        default:
                        {
                            if (_onBinaryMessage!=null)
                            {
                                if (lastFrame)
                                {
                                    _onBinaryMessage.onMessage(array,buffer.getIndex(),buffer.length());
                                }
                                else   
                                {
                                    if (_connection.getMaxBinaryMessageSize()>=0)
                                    {
                                        if (buffer.length()>_connection.getMaxBinaryMessageSize())
                                        {
                                            _connection.close(WebSocketConnectionD07.CLOSE_LARGE,"Message size > "+_connection.getMaxBinaryMessageSize());
                                            if (_aggregate!=null)
                                                _aggregate.clear();
                                            _opcode=-1;
                                        }
                                        else
                                        {
                                            _opcode=opcode;
                                            if (_aggregate==null)
                                                _aggregate=new ByteArrayBuffer(_connection.getMaxBinaryMessageSize());
                                            _aggregate.put(buffer);
                                        }
                                    }
                                }
                            }
                        }      
                    }
                }
                catch(ThreadDeath th)
                {
                    throw th;
                }
                catch(Throwable th)
                {
                    Log.warn(th);
                }
            }
        }

        public void close(int code,String message)
        {
            _connection.close(code,message);
        }

        public String toString()
        {
            return WebSocketConnectionD07.this.toString()+"FH";
        }
    }

    /* ------------------------------------------------------------ */
    private interface IdleCheck
    {
        void access(EndPoint endp);
    }

    /* ------------------------------------------------------------ */
    public void handshake(HttpServletRequest request, HttpServletResponse response, String origin, String subprotocol) throws IOException
    {
        String uri=request.getRequestURI();
        String query=request.getQueryString();
        if (query!=null && query.length()>0)
            uri+="?"+query;
        String key = request.getHeader("Sec-WebSocket-Key");
        
        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",hashKey(key));
        if (subprotocol!=null)
            response.addHeader("Sec-WebSocket-Protocol",subprotocol);
        response.sendError(101);

        if (_onFrame!=null)
            _onFrame.onHandshake(_connection);
        _webSocket.onOpen(_connection);
    }

    /* ------------------------------------------------------------ */
    public static String hashKey(String key)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(key.getBytes("UTF-8"));
            md.update(MAGIC);
            return new String(B64Code.encode(md.digest()));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
