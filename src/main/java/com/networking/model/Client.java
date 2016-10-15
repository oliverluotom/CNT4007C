package com.networking.model;

import com.networking.*;
import com.networking.config.*;
import com.networking.misc.*;

import java.io.*;
import java.net.*;
import java.util.*;

import static com.networking.config.CommonConfig.*;

/**
 * Does all of the work for managing a single instance of the bittorrent client.
 */
public class Client implements Runnable {

    private final PeerConfig clientCfg;
    private final ArrayList<Peer> peers = new ArrayList<Peer>();
    private final BitSet bitfield;
    private final byte[][] fileMap;
    private final Queue<Integer> pieceQueue = new LinkedList<Integer>();

    public Client(PeerConfig clientCfg) {
        this.clientCfg = clientCfg;
        // initialize bitfield
        bitfield = new BitSet(getFileSize());
        fileMap = new byte[getNumFilePieces()][];
        if (clientCfg.hasFile()) {
            bitfield.flip(0, getFileSize());
            // set up fileMap (map of piece idx to the actual piece)
            int pieceIdx = 0;
            for (int byteLo = 0; byteLo < getFileSize(); byteLo += getPieceSize()) {
                // take [byteLo, byteLo+CommonConfig.getPieceSize()-1]
                // len helps us for last byte[] that could be missing
                int len = Math.min(getPieceSize(), getFileSize()-byteLo);
                byte[] pieceArr = new byte[len];
                for (int bytePtr = byteLo; bytePtr < (byteLo+len); bytePtr++) {
                    pieceArr[bytePtr-byteLo] = getFile()[bytePtr];
                }
                fileMap[pieceIdx++] = pieceArr;
            }
        } else {
            // fill in queue of missing pieces (all of them at the beginning)
            for (int i = 0; i < getNumFilePieces(); i++) {
                pieceQueue.add(i);
            }
        }
    }

    public int getClientID() {
        return clientCfg.getPeerID();
    }

    public BitSet getBitfield() {
        return bitfield;
    }

    public byte[] getPiece(int pieceId) {
        if (pieceId >= fileMap.length) return null;
        return fileMap[pieceId];
    }

    private synchronized int numPeersDone() {
        int cnt = 0;
        for (Peer p : peers) {
            if (p.hasCompleteFile()) cnt++;
        }
        return cnt;
    }

    public synchronized int getNumMissingPieces() {
        int cnt = 0;
        for (int i = 0; i < fileMap.length; i++) if (fileMap[i] == null) cnt++;
        return cnt;
    }

    public synchronized int getMissingPiece(BitSet bitfield) {
        int count = pieceQueue.size();
        while (count-->0) {
            int pop = pieceQueue.poll();
            if (bitfield.get(pop)) return pop;
            pieceQueue.add(pop); // add to end of queue
        }
        return -1;
    }

    public synchronized void setPiece(int pieceId, byte[] pieceArr) {
        bitfield.set(pieceId, true);
        fileMap[pieceId] = pieceArr;
        for (Peer p : peers) {
            try {
                p.sendHavePacket(pieceId);
            } catch (IOException ex) {
                Bootstrap.stackExit(ex);
            }
        }
    }

    public synchronized void dataUnchoke() {
        // will be called by a timer asynchronously
        TreeMap<Double, Peer> rateMap = new TreeMap<Double, Peer>(Collections.reverseOrder());
        for (Peer p : peers) {
            double rate = p.getDownloadRate();
            if (getNumMissingPieces() == 0) rate = 1.; // make all peers equals
            if (p.hasCompleteFile()) rate = 0.; // we want peers with complete file to sink down...
            rateMap.put(rate, p);
        }
        int idx = 0;
        ArrayList<Integer> neighborIDs = new ArrayList<Integer>();
        try {
            for (Map.Entry<Double, Peer> ent : rateMap.entrySet()) {
                if (idx < getPreferredCount()) {
                    //unchoke
                    ent.getValue().setDataChoke(false);
                    neighborIDs.add(ent.getValue().getPeerID());
                } else {
                    //choke
                    ent.getValue().setDataChoke(true);
                }
                idx++;
            }
        } catch (IOException ex) {
            Bootstrap.stackExit(ex);
        }
        Logger.INSTANCE.println("Peer <" + getClientID() + "> has the preferred neighbors " + neighborIDs.toString() + ".");
    }

    public synchronized void randomUnchoke() {
        // will be called by a timer asynchronously
        ArrayList<Peer> chokedPeers = new ArrayList<Peer>();
        for (Peer p : peers) {
            if (p.isChoked() && !p.hasCompleteFile()) {
                chokedPeers.add(p);
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

    public synchronized void addPeer(Peer peer) {
        peers.add(peer);
        peer.start();
    }

    private synchronized void connect(PeerConfig pConfig) throws IOException {
        // open socket to pConfig.
        Socket socket = new Socket(pConfig.getHost(), pConfig.getPort());
        Peer p = new Peer(socket, this);
        addPeer(p);
        Logger.INSTANCE.println("Peer <" + getClientID() + "> makes a connection to Peer <" + p.getPeerID() + ">");
    }

    public void run() {
        Logger.INSTANCE.println("Starting client with ID <" + getClientID() + "> on port <" + clientCfg.getPort() + ">");
        // connect to lower peers
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
        // start data choker
        new Thread("Data Unchoker") {
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
        // start random unchoker
        /*new Thread("Random Unchoker") {
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
        }.start();*/
        // start shutdown Thread
        new Thread("Shutdown") {
            public void run() {
                while (true) {
                    if (numPeersDone() == PeerConfig.PEER_CONFIGS.size()-1) {
                        Logger.INSTANCE.println("Peer <" + getClientID() + "> terminating since all peers are done downloading.");
                        System.exit(0);
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) { continue; }
                }
            }
        }.start();
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
}
