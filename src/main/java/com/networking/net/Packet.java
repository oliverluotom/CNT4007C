package com.networking.net;

/**
 * A packet being sent or received over the net.
 * TODO: make a PacketBuilder instead of "mergePayloads".
 */
public class Packet {

    public enum PacketType {
        CHOKE,
        UNCHOKE,
        INTERESTED,
        NOT_INTERESTED,
        HAVE,
        BITFIELD,
        REQUEST,
        PIECE
    }

    private final PacketType packetType;
    private final byte[] payload;

    public Packet(PacketType packetType, byte[] payload) {
        this.packetType = packetType;
        this.payload = payload;
    }

    public PacketType getPacketType() {
        return packetType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public static byte[] mergePayloads(byte[] p1, byte[] p2) {
        byte[] merged = new byte[p1.length+p2.length];
        for (int i = 0; i < p1.length; i++) {
            merged[i] = p1[i];
        }
        for (int i = 0; i < p2.length; i++) {
            merged[i+p1.length] = p2[i];
        }
        return merged;
    }

    public static byte[] serializeInt(int v) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte)((v>>>24) & 0xFF);
        bytes[1] = (byte)((v>>>16) & 0xFF);
        bytes[2] = (byte)((v>>>8) & 0xFF);
        bytes[3] = (byte)((v>>>0) & 0xFF);
        return bytes;
    }

    public static int deserializeInt(byte[] bytes) {
        int ch1 = (int) (bytes[0] & 0xFF); // 0xFF converts UNSIGNED byte to int
        int ch2 = (int) (bytes[1] & 0xFF);
        int ch3 = (int) (bytes[2] & 0xFF);
        int ch4 = (int) (bytes[3] & 0xFF);
        return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));
    }
}
