package com.networking.model;

import com.networking.*;
import com.networking.config.*;
import com.networking.misc.*;
import com.networking.net.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Handles a peer that we're connected to.
 */
public class Peer extends Thread {
   /************************************************************************
    * Fields                                                               *
    ************************************************************************/
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final String HANDSHAKE_HEADER_STR = "P2PFILESHARINGPROJ";
    private static final byte[] HANDSHAKE_HEADER_BYTES =
            HANDSHAKE_HEADER_STR.getBytes(ASCII);
    private static final int BLANK_BYTE_LENGTH = 10;

    private final int peerID;
    private final Client client;

    private final Object SOCKET_LOCK = new Object();
    private final DataOutputStream dos;
    private final DataInputStream dis;

    private long timeCreated = System.currentTimeMillis();
    private final Object BITFIELD_LOCK = new Object();
    private BitSet bitfield = new BitSet(); //tracks which pieces peer has

    private final Object CHOKE_LOCK = new Object();
    private boolean dataChoked = true; //initially everyone is data choked
    private boolean randomChoked = true; //initially everyone is randomly choked
    private boolean areWeChoked = true; //has this peer choked our client

    private long totalBytesDownloaded = 0;
    private boolean interested = false; //initially not interested

    private boolean requested = false;

   /************************************************************************
    * Interface                                                            *
    ************************************************************************/
    /* =============== Initializors =============== */
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
    }

    /* =============== Accessors =============== */
    public int getPeerID() {
        return peerID;
    }

    public Client getClient() {
        return client;
    }

    public double getDownloadRate() {
        if (System.currentTimeMillis() == timeCreated) return 0.;
        return 1.*totalBytesDownloaded/(System.currentTimeMillis()-timeCreated);
    }

    public boolean isChoked() {
        synchronized (CHOKE_LOCK) {
            return dataChoked && randomChoked;
        }
    }

    // only gets called by Peer thread
    public boolean areWeChoked() {
        return areWeChoked;
    }

    // can get called by Client threads as well as peer thread
    public boolean hasCompleteFile() {
        synchronized (BITFIELD_LOCK) {
            for (int i = 0; i < CommonConfig.getNumFilePieces(); i++) {
                if (!bitfield.get(i)) return false;
            }
            return true;
        }
    }

    /* =============== Mutators =============== */
    // only gets called by Peer thread
    public void setAreWeChoked(boolean val) {
        areWeChoked = val;
    }

    public void setDataChoke(boolean val) throws IOException {
        synchronized (CHOKE_LOCK) {
            boolean isOldChoked = isChoked();
            dataChoked = val;
            boolean isNewChoked = isChoked();

            // TODO: May need to make this a "refreshChoke()" function (to avoid weirdness).
            // Actually, probably not.
            if (isOldChoked && !isNewChoked) {
                //unchoking, we reset timer/downloaded bytes since
                //download rate wants for LAST unchoked interval
                totalBytesDownloaded = 0;
                timeCreated = System.currentTimeMillis();
                sendUnchokePacket();
            } else if (!isOldChoked && isNewChoked) {
                //choking
                sendChokePacket();
            }
        }
    }

    public void setRandomChoke(boolean val) throws IOException {
        synchronized (CHOKE_LOCK) {
            boolean isOldChoked = isChoked();
            randomChoked = val;
            boolean isNewChoked = isChoked();
            if (isOldChoked && !isNewChoked) {
                //unchoking, we reset timer/downloaded bytes since
                //download rate wants for LAST unchoked interval
                totalBytesDownloaded = 0;
                sendUnchokePacket();
            } else if (!isOldChoked && isNewChoked) {
                //choking
                sendChokePacket();
            }
        }
    }

    /* =============== Behaviors =============== */
    @Override
    public void run() {
        try {
            // send bitfield message
            sendBitfieldPacket();
            // read packets!
            do {
                Packet p = readPacket();
                if (p == null) break;
                handlePacket(p);
                // request download after we read each packet
                requestDownload();
            } while (true);
        } catch (IOException ex) {
            Bootstrap.stackExit(ex);
        }
    }

    // gets called by Client
    public void sendHavePacket(int pieceID) throws IOException {
        Packet havePacket = new Packet(Packet.PacketType.HAVE, Packet.serializeInt(pieceID));
        sendPacket(havePacket);
    }

   /************************************************************************
    * Private                                                              *
    ************************************************************************/
    /* =============== Network Commands =============== */
    // only called by peer thread
    private void requestDownload() throws IOException {
        // if we're choked or there's already a download request to this peer,
        // we can't download
        if (areWeChoked() || requested) return;
        int requestPiece = getClient().getMissingPiece(bitfield);
        if (requestPiece == -1) {
            // this peer doesnt have a piece we need, or we're done.
            return;
        }
        sendRequestPacket(requestPiece);
        requested = true;
    }

    /* =============== Packet Senders =============== */
    // potentially called by client thread using setDataChoke/setRandomChoke
    private void sendChokePacket() throws IOException {
        // we're choking this peer
        Packet chokePacket = new Packet(Packet.PacketType.CHOKE, new byte[0]);
        sendPacket(chokePacket);
    }

    // potentially called by client thread using setDataChoke/setRandomChoke
    private void sendUnchokePacket() throws IOException {
        // we're unchoking this peer
        Packet unchokePacket = new Packet(Packet.PacketType.UNCHOKE, new byte[0]);
        sendPacket(unchokePacket);
    }

    // only called by peer thread
    private void sendRequestPacket(int pieceID) throws IOException {
        Packet requestPacket = new Packet(Packet.PacketType.REQUEST, Packet.serializeInt(pieceID));
        sendPacket(requestPacket);
    }

    // only called by peer thread
    private void sendPiecePacket(int pieceId, byte[] pieceArr) throws IOException {
        byte[] payload = Packet.mergePayloads(Packet.serializeInt(pieceId), pieceArr);
        Packet piecePacket = new Packet(Packet.PacketType.PIECE, payload);
        sendPacket(piecePacket);
    }

    // only called by peer thread
    private void sendBitfieldPacket() throws IOException {
        byte[] payload = getClient().getBitfieldArray();
        Packet bitPacket = new Packet(Packet.PacketType.BITFIELD, payload);
        sendPacket(bitPacket);
    }

    // only called by peer thread
    private void sendInterestedPacket() throws IOException {
        Packet intPacket = new Packet(Packet.PacketType.INTERESTED, new byte[0]);
        sendPacket(intPacket);
    }

    // only called by peer thread
    private void sendNotInterestedPacket() throws IOException {
        Packet nIntPacket = new Packet(Packet.PacketType.NOT_INTERESTED, new byte[0]);
        sendPacket(nIntPacket);
    }

    /* =============== Packet Handlers =============== */
    // only called by peer thread
    private void handlePacket(Packet packet) throws IOException {
        switch (packet.getPacketType()) {
            case CHOKE:
                handleChokePacket(packet);
                break;
            case UNCHOKE:
                handleUnchokePacket(packet);
                break;
            case INTERESTED:
                handleInterestedPacket(packet);
                break;
            case NOT_INTERESTED:
                handleNotInterestedPacket(packet);
                break;
            case HAVE:
                handleHavePacket(packet);
                break;
            case BITFIELD:
                handleBitfieldPacket(packet);
                break;
            case REQUEST:
                handleRequestPacket(packet);
                break;
            case PIECE:
                handlePiecePacket(packet);
                break;
            default:
                Logger.INSTANCE.println("Unhandled packet type: " + packet.getPacketType());
        }
    }

    private synchronized void handleChokePacket(Packet packet) throws IOException {
        // client just got choked by this peer
        // can't do anything in this case
        Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> is choked by Peer <" + getPeerID() + ">");
        setAreWeChoked(true);
    }

    private synchronized void handleUnchokePacket(Packet packet) throws IOException {
        // client just got unchoked by this peer
        // in this case, we get a piece that we're missing and we request it!
        Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> is unchoked by Peer <" + getPeerID() + ">");
        setAreWeChoked(false);
    }

    private synchronized void handleRequestPacket(Packet packet) throws IOException {
        int pieceId = Packet.deserializeInt(packet.getPayload());
        byte[] pieceArr = getClient().getPiece(pieceId);
        if (pieceArr != null) {
            // this line isn't actually required by project spec.
            //Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> got piece <" + pieceId + "> requested BY Peer <" + getPeerID() + ">");
            sendPiecePacket(pieceId, pieceArr);
        } else {
            // invalid piece...throw runtime exception
            throw new RuntimeException("Invalid piece <" + pieceId + "> requested by Peer <" + getPeerID() + ">");
        }
    }

    private synchronized void handlePiecePacket(Packet packet) throws IOException {
        byte[] payload = packet.getPayload();
        int pieceId = Packet.deserializeInt(new byte[] {payload[0],payload[1],payload[2],payload[3]});
        byte[] piece = new byte[payload.length-4];
        for (int i = 0; i < piece.length; i++) {
            piece[i] = payload[i+4];
        }
        getClient().setPiece(pieceId, piece);
        totalBytesDownloaded += piece.length;
        requested = false;
        int missing = getClient().getNumMissingPieces();
        int numPieces = CommonConfig.getNumFilePieces()-missing;
        Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> has downloaded the piece <" + pieceId + "> from Peer <" + getPeerID() + ">.\nNow the number of pieces it has is " + numPieces + ".");
        if (missing == 0) {
            Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> has downloaded the complete file.");
        }
    }

    private void handleHavePacket(Packet packet) throws IOException {
        int pieceId = Packet.deserializeInt(packet.getPayload());
        Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> received the 'have' message from Peer <" + getPeerID() + "> for the piece <" + pieceId + ">");
        synchronized (BITFIELD_LOCK) {
            bitfield.set(pieceId, true);
        }
        if (!getClient().hasPiece(pieceId)) {
            sendInterestedPacket();
        }
    }

    // handles only get called by peer thread
    private void handleBitfieldPacket(Packet packet) throws IOException {
        byte[] payload = packet.getPayload();
        synchronized (BITFIELD_LOCK) {
            bitfield = BitSet.valueOf(payload);
            for (int piece = 0; piece < CommonConfig.getNumFilePieces(); piece++) {
                if (bitfield.get(piece) && !getClient().hasPiece(piece)) {
                    // peer has <bit> that we don't have
                    sendInterestedPacket();
                    break;
                }
            }
        }
    }

    // handles only get called by peer thread
    private void handleInterestedPacket(Packet packet) throws IOException {
        interested = true;
        Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> received the 'interested' message from Peer <" + getPeerID() + ">");
    }

    // handles only get called by peer thread
    private void handleNotInterestedPacket(Packet packet) throws IOException {
        interested = false;
        Logger.INSTANCE.println("Peer <" + getClient().getClientID() + "> received the 'not interested' message from Peer <" + getPeerID() + ">");
    }

    /* =============== Base Packet Functions =============== */
    // this function blocks until it reads a full packet
    // only gets called by peer thread
    private Packet readPacket() {
        try {
            int payloadLength = dis.readInt();
            byte type = dis.readByte();
            byte[] payload = new byte[payloadLength];
            dis.readFully(payload);
            return new Packet(Packet.PacketType.values()[(int)type], payload);
        } catch (IOException ex) { return null; }
    }

    // this function sends a packet down the wire
    // potentially can get called by different threads
    private void sendPacket(Packet p) throws IOException {
        synchronized (SOCKET_LOCK) {
            dos.writeInt(p.getPayload().length);
            dos.write(p.getPacketType().ordinal());
            dos.write(p.getPayload(), 0, p.getPayload().length);
            dos.flush();
        }
    }
}
