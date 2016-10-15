package com.networking.net;

/**
 * A packet being sent or received over the net.
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

    public PacketType getType() {
        return packetType;
    }

    public byte[] getPayload() {
        return payload;
    }
}
