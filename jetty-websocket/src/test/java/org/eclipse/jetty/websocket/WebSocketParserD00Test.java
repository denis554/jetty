package org.eclipse.jetty.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpHeaderValues;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketParserD00Test
{
    private ByteArrayBuffer _in;
    private Handler _handler;
    private WebSocketParser _parser;

    @Before
    public void setUp() throws Exception
    {
        WebSocketBuffers buffers = new WebSocketBuffers(1024);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        _handler = new Handler();
        _parser=new WebSocketParserD00(buffers, endPoint,_handler);
        _in = new ByteArrayBuffer(2048);
        endPoint.setIn(_in);
    }

    @Test
    public void testCache() throws Exception
    {
        assertEquals(HttpHeaderValues.UPGRADE_ORDINAL ,((CachedBuffer)HttpHeaderValues.CACHE.lookup("Upgrade")).getOrdinal());
    }

    @Test
    public void testOneUtf8() throws Exception
    {
        _in.put((byte)0x00);
        _in.put("Hello World".getBytes(StringUtil.__UTF8));
        _in.put((byte)0xff);

        int filled =_parser.parseNext();

        assertEquals(13,filled);
        assertEquals("Hello World",_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }

    @Test
    public void testTwoUtf8() throws Exception
    {
        _in.put((byte)0x00);
        _in.put("Hello World".getBytes(StringUtil.__UTF8));
        _in.put((byte)0xff);
        _in.put((byte)0x00);
        _in.put("Hell\uFF4F W\uFF4Frld".getBytes(StringUtil.__UTF8));
        _in.put((byte)0xff);

        int filled =_parser.parseNext();

        assertEquals(30,filled);
        assertEquals("Hello World",_handler._data.get(0));
        assertFalse(_parser.isBufferEmpty());
        assertFalse(_parser.getBuffer()==null);

        filled =_parser.parseNext();

        assertEquals(0,filled);
        assertEquals("Hell\uFF4f W\uFF4Frld",_handler._data.get(1));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }

    @Test
    public void testOneBinary() throws Exception
    {
        _in.put((byte)0x80);
        _in.put((byte)11);
        _in.put("Hello World".getBytes(StringUtil.__UTF8));

        int filled =_parser.parseNext();

        assertEquals(13,filled);
        assertEquals("Hello World",_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }

    @Test
    public void testTwoBinary() throws Exception
    {
        _in.put((byte)0x80);
        _in.put((byte)11);
        _in.put("Hello World".getBytes(StringUtil.__UTF8));

        byte[] data = new byte[150];
        for (int i=0;i<data.length;i++)
            data[i]=(byte)('0'+(i%10));

        _in.put((byte)0x80);
        _in.put((byte)(0x80|(data.length>>7)));
        _in.put((byte)(data.length&0x7f));
        _in.put(data);


        int filled =_parser.parseNext();
        assertEquals(13+3+data.length,filled);
        assertEquals("Hello World",_handler._data.get(0));
        assertFalse(_parser.isBufferEmpty());
        assertFalse(_parser.getBuffer()==null);

        filled =_parser.parseNext();
        assertEquals(0,filled);
        String got=_handler._data.get(1);
        assertEquals(data.length,got.length());
        assertTrue(got.startsWith("012345678901234567890123"));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }


    private class Handler implements WebSocketParser.FrameHandler
    {
        public List<String> _data = new ArrayList<String>();

        public void onFrame(byte flags, byte opcode, Buffer buffer)
        {
            _data.add(buffer.toString(StringUtil.__UTF8));
        }

        public void close(int code,String message)
        {
        }
    }
}
