package com.networking.model;

import com.networking.net.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;

/**
 * Handles a peer that we're connected to.
 */
public class Peer extends Thread {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final String HANDSHAKE_HEADER_STR = "P2PFILESHARINGPROJ";
    private static final byte[] HANDSHAKE_HEADER_BYTES =
        HANDSHAKE_HEADER_STR.getBytes(ASCII);
    private static final int BLANK_BYTE_LENGTH = 10;

    private final int peerID;
    private final Client client;
    private final DataOutputStream dos;
    private final DataInputStream dis;
    private final PacketHandler packetHandler;
    private boolean dataChoked = true; // initially everyone is data choked
    private boolean randomChoke = true; // initially everyone is randomly choked

    public Peer(Socket sock, Client client) throws IOException {
        this.client = client;
        dos = new DataOutputStream(sock.getOutputStream());
        dis = new DataInputStream(sock.getInputStream());
        // write handshake
        dos.write(HANDSHAKE_HEADER_BYTES, 0, HANDSHAKE_HEADER_BYTES.length);
        for (int i = 0; i < BLANK_BYTE_LENGTH; i++) {
            dos.writeByte(0x0);
        }
        dos.writeInt(client.getClientID());
        dos.flush(); // flush stream
        // read handshake
        byte[] headerBytes = new byte[HANDSHAKE_HEADER_BYTES.length];
        dis.readFully(headerBytes);
        String header = new String(headerBytes, ASCII);
        if (!header.equals(HANDSHAKE_HEADER_STR))
            throw new RuntimeException("Invalid peer handshake, got <" + header + ">");
        dis.skipBytes(BLANK_BYTE_LENGTH);
        peerID = dis.readInt();
        packetHandler = new PacketHandler(this);
    }

    public int getPeerID() {
        return peerID;
    }

    public Client getClient() {
        return client;
    }

    public void run() {
        // read packets!
        do {
            Packet p = readPacket();
            if (p == null) break;
            packetHandler.handlePacket(p);
        } while (true);
    }

    // this function blocks until it reads a full packet
    private Packet readPacket() {
        try {
            int payloadLength = dis.readInt();
            byte type = dis.readByte();
            byte[] payload = new byte[payloadLength];
            dis.readFully(payload);
            return new Packet(Packet.PacketType.values()[(int)type], payload);
        } catch (IOException ex) { return null; }
    }
}
