package org.eclipse.jetty.websocket;

import java.io.IOException;

public interface WebSocket
{
    public final byte LENGTH_FRAME=(byte)0x80;
    public final byte SENTINEL_FRAME=(byte)0x00;
    void onConnect(Outbound outbound);
    void onMessage(byte opcode,String data);
    void onFragment(boolean more,byte opcode,byte[] data, int offset, int length);
    void onMessage(byte opcode,byte[] data, int offset, int length);
    void onDisconnect();
    
    public interface Outbound
    {
        void sendMessage(String data) throws IOException;
        void sendMessage(byte opcode,String data) throws IOException;
        void sendMessage(byte opcode,byte[] data, int offset, int length) throws IOException;
        void sendFragment(boolean more,byte opcode,byte[] data, int offset, int length) throws IOException;
        void disconnect();
        boolean isOpen();
    }
}
