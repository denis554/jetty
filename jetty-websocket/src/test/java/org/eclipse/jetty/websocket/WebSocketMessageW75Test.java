package org.eclipse.jetty.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.StringUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @version $Revision$ $Date$
 */
public class WebSocketMessageW75Test
{
    private static Server _server;
    private static Connector _connector;
    private static TestWebSocket _serverWebSocket;

    @BeforeClass
    public static void startServer() throws Exception
    {
        _server = new Server();
        _connector = new SelectChannelConnector();
        _server.addConnector(_connector);
        WebSocketHandler wsHandler = new WebSocketHandler()
        {
            @Override
            protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                return _serverWebSocket = new TestWebSocket();
            }
        };
        wsHandler.setHandler(new DefaultHandler());
        _server.setHandler(wsHandler);
        _server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testServerSendBigStringMessage() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "ISO-8859-1"));
        String responseLine = reader.readLine();
        assertTrue(responseLine.startsWith("HTTP/1.1 101 Web Socket Protocol Handshake"));
        // Read until we find an empty line, which signals the end of the http response
        String line;
        while ((line = reader.readLine()) != null)
            if (line.length() == 0)
                break;

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);

        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < 64 * 1024 / text.length(); ++i)
            message.append(text);
        _serverWebSocket.outbound.sendMessage(message.toString());

        // Read until we get 0xFF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true)
        {
            int read = input.read();
            baos.write(read);
            if (read == 0xFF)
                break;
        }
        baos.close();
        byte[] bytes = baos.toByteArray();
        String result = StringUtil.printable(bytes);
        assertTrue(result.startsWith("0x00"));
        assertTrue(result.endsWith("0xFF"));
        assertEquals(message.length() + "0x00".length() + "0xFF".length(), result.length());
    }

    @Test
    public void testServerSendBigBinaryMessage() throws Exception
    {
        Socket socket = new Socket("localhost", _connector.getLocalPort());
        OutputStream output = socket.getOutputStream();
        output.write(
                ("GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n").getBytes("ISO-8859-1"));
        output.flush();

        // Make sure the read times out if there are problems with the implementation
        socket.setSoTimeout(1000);

        InputStream input = socket.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "ISO-8859-1"));
        String responseLine = reader.readLine();
        assertTrue(responseLine.startsWith("HTTP/1.1 101 Web Socket Protocol Handshake"));
        // Read until we find an empty line, which signals the end of the http response
        String line;
        while ((line = reader.readLine()) != null)
            if (line.length() == 0)
                break;

        assertTrue(_serverWebSocket.awaitConnected(1000));
        assertNotNull(_serverWebSocket.outbound);

        // Server sends a big message
        StringBuilder message = new StringBuilder();
        String text = "0123456789ABCDEF";
        for (int i = 0; i < 64 * 1024 / text.length(); ++i)
            message.append(text);
        byte[] data = message.toString().getBytes("UTF-8");
        _serverWebSocket.outbound.sendMessage(data,0,data.length);

        // Length of the message is 65536, so the length will be encoded as 0x84 0x80 0x00
        int frame = input.read();
        assertEquals(0x80, frame);
        int length1 = input.read();
        assertEquals(0x84, length1);
        int length2 = input.read();
        assertEquals(0x80, length2);
        int length3 = input.read();
        assertEquals(0x00, length3);
        int read = 0;
        while (read < data.length)
        {
            int b = input.read();
            assertTrue(b != -1);
            ++read;
        }
    }

    private static class TestWebSocket implements WebSocket
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Connection outbound;

        public void onConnect(Connection outbound)
        {
            this.outbound = outbound;
            latch.countDown();
        }

        private boolean awaitConnected(long time) throws InterruptedException
        {
            return latch.await(time, TimeUnit.MILLISECONDS);
        }

        public void onDisconnect(int code,String data)
        {
        }

    }
}
