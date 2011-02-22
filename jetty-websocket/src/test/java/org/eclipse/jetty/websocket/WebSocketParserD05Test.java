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
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.Before;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketParserD05Test
{
    private ByteArrayBuffer _in;
    private Handler _handler;
    private WebSocketParser _parser;
    private byte[] _mask = new byte[] {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff};

    @Before
    public void setUp() throws Exception
    {
        WebSocketBuffers buffers = new WebSocketBuffers(1024);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        _handler = new Handler();
        _parser=new WebSocketParserD05(buffers, endPoint,_handler,true);
        _in = new ByteArrayBuffer(2048)
        {
            {
                // add the mask
                super.put(_mask,0,4);
            }

            @Override
            public void poke(int index, byte b)
            {
                super.poke(index,(byte)(b^_mask[index%4]));
            }

            @Override
            public int poke(int index, Buffer src)
            {
                return poke(index,src.asArray(),0,src.length());
            }

            @Override
            public int poke(int index, byte[] b, int offset, int length)
            {
                byte[] mb = new byte[b.length];
                for (int i=length;i-->0;)
                    mb[offset+i]=(byte)(b[offset+i]^_mask[(index+i)%4]);
                return super.poke(index,mb,offset,length);
            }

            @Override
            public int put(Buffer src)
            {
                return put(src.asArray(),0,src.length());
            }

            @Override
            public void put(byte b)
            {
                super.put((byte)(b^_mask[getIndex()%4]));
            }

            @Override
            public int put(byte[] b, int offset, int length)
            {
                byte[] mb = new byte[b.length];
                for (int i=length;i-->0;)
                    mb[offset+i]=(byte)(b[offset+i]^_mask[(getIndex()+i)%4]);
                return super.put(mb,offset,length);
            }

            @Override
            public int put(byte[] b)
            {
                return put(b,0,b.length);
            }
            
        };
        
        
        endPoint.setIn(_in);
    }

    @Test
    public void testCache() throws Exception
    {
        assertEquals(HttpHeaderValues.UPGRADE_ORDINAL ,((CachedBuffer)HttpHeaderValues.CACHE.lookup("Upgrade")).getOrdinal());
    }

    @Test
    public void testShortText() throws Exception
    {
        _in.put((byte)0x00);
        _in.put((byte)11);
        _in.put("Hello World".getBytes(StringUtil.__UTF8));

        int filled =_parser.parseNext();

        assertEquals(17,filled);
        assertEquals("Hello World",_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testShortUtf8() throws Exception
    {
        String string = "Hell\uFF4f W\uFF4Frld";
        byte[] bytes = string.getBytes("UTF-8");
        
        _in.put((byte)0x00);
        _in.put((byte)bytes.length);
        _in.put(bytes);

        int filled =_parser.parseNext();

        assertEquals(bytes.length+6,filled);
        assertEquals(string,_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testMediumText() throws Exception
    {
        String string = "Hell\uFF4f Medium W\uFF4Frld ";
        for (int i=0;i<4;i++)
            string = string+string;
        string += ". The end.";
        
        byte[] bytes = string.getBytes("UTF-8");
        
        _in.put((byte)0x00);
        _in.put((byte)0x7E);
        _in.put((byte)(bytes.length>>8));
        _in.put((byte)(bytes.length&0xff));
        _in.put(bytes);

        int filled =_parser.parseNext();

        assertEquals(bytes.length+8,filled);
        assertEquals(string,_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }
    
    @Test
    public void testLongText() throws Exception
    {

        WebSocketBuffers buffers = new WebSocketBuffers(0x20000);
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        WebSocketParser parser=new WebSocketParserD01(buffers, endPoint,_handler);
        ByteArrayBuffer in = new ByteArrayBuffer(0x20000);
        endPoint.setIn(in);
        
        
        String string = "Hell\uFF4f Big W\uFF4Frld ";
        for (int i=0;i<12;i++)
            string = string+string;
        string += ". The end.";
        
        byte[] bytes = string.getBytes("UTF-8");
        
        in.put((byte)0x00);
        in.put((byte)0x7F);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)0x00);
        in.put((byte)(bytes.length>>16));
        in.put((byte)((bytes.length>>8)&0xff));
        in.put((byte)(bytes.length&0xff));
        in.put(bytes);

        int filled =parser.parseNext();

        assertEquals(bytes.length+10,filled);
        assertEquals(string,_handler._data.get(0));
        assertTrue(parser.isBufferEmpty());
        assertTrue(parser.getBuffer()==null);
    }

    @Test
    public void testShortFragmentTest() throws Exception
    {
        _in.put((byte)0x80);
        _in.put((byte)0x06);
        _in.put("Hello ".getBytes(StringUtil.__UTF8));
        _in.put((byte)0x00);
        _in.put((byte)0x05);
        _in.put("World".getBytes(StringUtil.__UTF8));

        int filled =_parser.parseNext();

        assertEquals(19,filled);
        assertEquals(0,_handler._data.size());
        assertFalse(_parser.isBufferEmpty());
        assertFalse(_parser.getBuffer()==null);

        filled =_parser.parseNext();

        assertEquals(0,filled);
        assertEquals("Hello World",_handler._data.get(0));
        assertTrue(_parser.isBufferEmpty());
        assertTrue(_parser.getBuffer()==null);
    }


    private class Handler implements WebSocketParser.FrameHandler
    {
        Utf8StringBuilder _utf8 = new Utf8StringBuilder();
        public List<String> _data = new ArrayList<String>();

        public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
        {
            if (more)
                _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
            else if (_utf8.length()==0)
                _data.add(opcode,buffer.toString("utf-8"));
            else
            {
                _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                _data.add(opcode,_utf8.toString());
                _utf8.reset();
            }
        }
    }
}
