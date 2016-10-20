package com.networking.model;

import com.networking.*;
import com.networking.config.*;
import com.networking.misc.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static com.networking.config.CommonConfig.*;

/**
 * Does all of the work for managing a single instance of the bittorrent client.
 */
public class Client implements Runnable {
    private final PeerConfig clientCfg;
    private final ArrayList<Peer> peers = new ArrayList<Peer>();

    private final Object BITFIELD_LOCK = new Object(); // lock for both things below
    private final BitSet piecesObtained; //tracks which pieces we have
    private final byte[][] fileMap; //each row is a piece

    private final Queue<Integer> pieceQueue = new LinkedList<Integer>();

    public Client(PeerConfig clientCfg) {
        this.clientCfg = clientCfg;

        piecesObtained = new BitSet(getNumFilePieces());
        fileMap = new byte[getNumFilePieces()][];

        if (clientCfg.hasFile()) {
            // Set the entire bitfield because we have every piece
            piecesObtained.flip(0, getNumFilePieces());
            // Set up the fileMap (map of piece idx to the actual piece)
            for(int byteLo = 0, pieceIdx = 0;
                    byteLo < getFileSize();
                    byteLo += getPieceSize()) {
                /*
                 Take [byteLo, byteLo+len-1] for the current piece.
                 len is always getPieceSize() for all but the last piece.
                 The last piece may be smaller than the piece size if the
                 file is not divisible by the piece size
                 */
                int len = Math.min(getPieceSize(), getFileSize()-byteLo);
                byte[] pieceArr = new byte[len];
                for (int bytePtr = byteLo; bytePtr < (byteLo+len); bytePtr++) {
                    pieceArr[bytePtr-byteLo] = getFile()[bytePtr];
                }
                // Store the current piece in the file map
                fileMap[pieceIdx++] = pieceArr;
            }
        } else {
            ArrayList<Integer> toAdd = new ArrayList<Integer>();
            for (int i = 0; i < getNumFilePieces(); i++) {
                toAdd.add(i);
            }
            Collections.shuffle(toAdd);
            pieceQueue.addAll(toAdd);
        }
    }

    public int getClientID() {
        return clientCfg.getPeerID();
    }

    public byte[] getBitfieldArray() {
        synchronized (BITFIELD_LOCK) {
            return piecesObtained.toByteArray();
        }
    }

    public boolean hasPiece(int pieceID) {
        synchronized (BITFIELD_LOCK) {
            return piecesObtained.get(pieceID);
        }
    }

    public byte[] getPiece(int pieceId) {
        synchronized (BITFIELD_LOCK) {
            if (pieceId >= fileMap.length) return null;
            return fileMap[pieceId];
        }
    }

    public int getNumMissingPieces() {
        synchronized (BITFIELD_LOCK) {
            return getNumFilePieces() - piecesObtained.cardinality();
        }
    }

    public int getMissingPiece(BitSet piecesOffered) {
        // This intentionally uses a queue, in order to avoid requesting
        // the same piece from two different peers.
        synchronized (pieceQueue) {
            int count = pieceQueue.size();
            while (count-->0) {
                int pop = pieceQueue.poll();
                if (piecesOffered.get(pop)) return pop;
                pieceQueue.add(pop); // add to end of queue
            }
        }
        return -1;
    }

    public int numPeersDone() {
        int cnt = 0;
        synchronized (peers) {
            for (Peer p : peers) if (p.hasCompleteFile()) ++cnt;
        }
        return cnt;
    }

    public void setPiece(int pieceID, byte[] pieceArr) {
        // set piece stuff
        synchronized (BITFIELD_LOCK) {
            piecesObtained.set(pieceID, true);
            fileMap[pieceID] = pieceArr;
        }
        // send 'have' packet to all peers
        synchronized (peers) {
            for (Peer p : peers) {
                try {
                    p.sendHavePacket(pieceID);
                } catch (IOException ex) {
                    Bootstrap.stackExit(ex);
                }
            }
        }
    }

    public void addPeer(Peer peer) {
        synchronized (peers) {
            peers.add(peer);
        }
        peer.start();
    }

    public void dataUnchoke() {
        // will be called by a timer asynchronously
        TreeMap<Double, Peer> rateMap = new TreeMap<Double, Peer>(Collections.reverseOrder());
        boolean hasMissingPiece = getNumMissingPieces() != 0;
        synchronized (peers) {
            for (Peer p : peers) {
                double rate = p.getDownloadRate();
                if (!hasMissingPiece) rate = 1.; // make all peers equals
                if (p.hasCompleteFile()) continue; // don't consider peers with full file
                rateMap.put(rate, p);
            }
        }
        int idx = 0;
        ArrayList<Integer> neighborIDs = new ArrayList<Integer>();
        try {
            for (Map.Entry<Double, Peer> ent : rateMap.entrySet()) {
                if (idx < getPreferredCount()) {
                    // unchoke
                    ent.getValue().setDataChoke(false);
                    neighborIDs.add(ent.getValue().getPeerID());
                } else {
                    // choke
                    ent.getValue().setDataChoke(true);
                }
                idx++;
            }
        } catch (IOException ex) {
            Bootstrap.stackExit(ex);
        }
        Logger.INSTANCE.println("Peer <" + getClientID() + "> has the preferred neighbors " + neighborIDs.toString() + ".");
    }

    public void randomUnchoke() {
        // will be called by a timer asynchronously
        ArrayList<Peer> chokedPeers = new ArrayList<Peer>();
        synchronized (peers) {
            for (Peer p : peers) {
                if (p.isChoked() && !p.hasCompleteFile()) {
                    chokedPeers.add(p);
                }
            }
        }
        Collections.shuffle(chokedPeers);
        if (!chokedPeers.isEmpty()) {
            // unchoke random peer!
            try {
                chokedPeers.get(0).setRandomChoke(false);
                Logger.INSTANCE.println("Peer <" + getClientID() + "> has the optimistically unchoked neighbor Peer <" + chokedPeers.get(0).getPeerID() + ">.");
            } catch (IOException ex) {
                Bootstrap.stackExit(ex);
            }
        }
    }

    @Override
    public void run() {
        Logger.INSTANCE.println(
                "Starting client with ID <" + getClientID() +
                "> on port <" + clientCfg.getPort() + ">");
        connectToLowerPeers();
        startDataUnchoker();
        startRandomUnchoker();
        startShutdownThread();
        // listen for higher peers
        try {
            ServerSocket server = new ServerSocket(clientCfg.getPort());
            do {
                Socket socket = server.accept();
                Peer p = new Peer(socket, this);
                addPeer(p);
                Logger.INSTANCE.println("Peer <" + getClientID() + "> is connected from Peer <" + p.getPeerID() + ">");
            } while(true);
        } catch (Exception ex) {
            Logger.INSTANCE.println("Error running server socket, terminating.");
            Bootstrap.stackExit(ex);
        }
    }

    private void connect(PeerConfig pConfig) throws IOException {
        // open socket to pConfig.
        Socket socket = new Socket(pConfig.getHost(), pConfig.getPort());
        Peer p = new Peer(socket, this);
        addPeer(p);
        Logger.INSTANCE.println("Peer <" + getClientID() + "> makes a connection to Peer <" + p.getPeerID() + ">");
    }

    private void writeFile() {
        File out = new File(
                "./peer_" + clientCfg.getPeerID() + "/" + CommonConfig.getFileName());
        out.getParentFile().mkdirs();
        try {
            FileOutputStream outstream = new FileOutputStream(out);
            for(int i = 0; i < fileMap.length; i++) {
                // write i-th piece to file
                outstream.write(fileMap[i]);
            }
            outstream.close();
        } catch (IOException e) {
            Bootstrap.stackExit(e);
        }
    }

    private void connectToLowerPeers() {
        for (PeerConfig pConfig : PeerConfig.PEER_CONFIGS) {
            if (pConfig.getPeerID() < clientCfg.getPeerID()) {
                try {
                    connect(pConfig);
                } catch (IOException ex) {
                    // fatally exit if we can't connect to a prior peer
                    Logger.INSTANCE.println("Error connecting to peer <" + pConfig.getPeerID() + ">, terminating.");
                    Bootstrap.stackExit(ex);
                }
            }
        }
    }

    private void startDataUnchoker() {
        new Thread("Data Unchoker Thread") {
            long lastChoke = 0;
            public void run() {
                while (true) {
                    long curTime = System.currentTimeMillis();
                    if ((curTime-lastChoke) >= 1000*CommonConfig.getDataUnchokeInterval()) {
                        lastChoke = curTime;
                        dataUnchoke();
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) { continue; }
                }
            }
        }.start();
    }

    private void startRandomUnchoker() {
        new Thread("Random Unchoker Thread") {
            long lastChoke = System.currentTimeMillis();
            public void run() {
                while (true) {
                    long curTime = System.currentTimeMillis();
                    if ((curTime-lastChoke) >= 1000*CommonConfig.getRandomUnchokeInterval()) {
                        lastChoke = curTime;
                        randomUnchoke();
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) { continue; }
                }
            }
        }.start();
    }

    private void startShutdownThread() {
        new Thread("Shutdown Thread") {
            public void run() {
                while (true) {
                    if (getNumMissingPieces() == 0 && numPeersDone() == PeerConfig.PEER_CONFIGS.size()-1) {
                        Logger.INSTANCE.println("Peer <" + getClientID() + "> terminating since all peers are done downloading.");
                        writeFile();
                        System.exit(0);
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) { continue; }
                }
            }
        }.start();
    }
}
