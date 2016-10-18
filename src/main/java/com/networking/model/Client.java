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
   /************************************************************************
    * Fields                                                               *
    ************************************************************************/
    private final PeerConfig clientCfg;
    private final ArrayList<Peer> peers = new ArrayList<Peer>();
    
    private final BitSet piecesObtained; //tracks which pieces we have
    private final byte[][] fileMap; //each row is a piece

   /************************************************************************
    * Interface                                                            *
    ************************************************************************/
    /* =============== Initializors =============== */
    public Client(PeerConfig clientCfg) {
        this.clientCfg = clientCfg;
        
        piecesObtained = new BitSet(getNumFilePieces());
        fileMap = new byte[getNumFilePieces()][];
        
        if (clientCfg.hasFile()) {
            /* Set the entire bitfield because we have every byte */
            piecesObtained.flip(0, getFileSize());
            /* Set up the fileMap (map of piece idx to the actual piece) */
            for(int byteLo = 0, pieceIdx = 0; 
                    byteLo < getFileSize();
                    byteLo += getPieceSize()) {
                /*
                 * Take [byteLo, byteLo+len-1] for the current piece.
                 * len is always getPieceSize() for all but the last piece.
                 * The last piece may be smaller than the piece size if the
                 * file is not divisible by the piece size 
                 */
                int len = Math.min(getPieceSize(), getFileSize()-byteLo);
                byte[] pieceArr = new byte[len];
                for (int bytePtr = byteLo; bytePtr < (byteLo+len); bytePtr++) {
                    pieceArr[bytePtr-byteLo] = getFile()[bytePtr];
                }
                /* Store the current piece in the file map */
                fileMap[pieceIdx++] = pieceArr;
            }
        } 
    }

    /* =============== Accessors =============== */
    public int getClientID() {
        return clientCfg.getPeerID();
    }

    public BitSet getBitfield() {
        return piecesObtained;
    }

    public synchronized byte[] getPiece(int pieceId) {
        if (pieceId >= fileMap.length) return null;
        return fileMap[pieceId];
    }
    
    public synchronized int getNumMissingPieces() {
        return piecesObtained.size() - piecesObtained.cardinality();
    }
    
    public synchronized int getMissingPiece(BitSet piecesOffered) {
        /* Find all pieces they have and we don't */
        List<Integer> validPieces = new ArrayList<Integer>();
        for(int i = 0; i < piecesOffered.length(); ++i){
            if(!piecesObtained.get(i) && piecesOffered.get(i)){
                validPieces.add(i);
            }
        }
        /* If there weren't any, fail */
        if(validPieces.size() == 0) return -1;
        /* Otherwise return a random piece from the valid set */
        int randomPiece = (int) (validPieces.size()*Math.random());
        return validPieces.get(randomPiece);
    }
    
    public synchronized int numPeersDone() {
        int cnt = 0;
        for (Peer p : peers) if (p.hasCompleteFile()) ++cnt;
        return cnt;
    }

    /* =============== Mutators =============== */
    public synchronized void setPiece(int pieceID, byte[] pieceArr) {
        piecesObtained.set(pieceID, true);
        fileMap[pieceID] = pieceArr;
        for (Peer p : peers) {
            try {
                p.sendHavePacket(pieceID);
            } catch (IOException ex) {
                Bootstrap.stackExit(ex);
            }
        }
    }
    
    public synchronized void addPeer(Peer peer) {
        peers.add(peer);
        peer.start();
    }

    //placeholder for matt k.
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
    
    /* =============== Behaviors =============== */
    @Override
    public void run() {
        Logger.INSTANCE.println(
                "Starting client with ID <" + getClientID() + 
                "> on port <" + clientCfg.getPort() + ">");
        connectToLowerPeers();
        startDataUnchoker();
        //startRandomUnchoker();
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
    
   /************************************************************************
    * Private                                                              *
    ************************************************************************/
    /* =============== Connection =============== */
    private synchronized void connect(PeerConfig pConfig) throws IOException {
        // open socket to pConfig.
        Socket socket = new Socket(pConfig.getHost(), pConfig.getPort());
        Peer p = new Peer(socket, this);
        addPeer(p);
        Logger.INSTANCE.println("Peer <" + getClientID() + "> makes a connection to Peer <" + p.getPeerID() + ">");
    }
    
    /* =============== Run Subtasks =============== */
    private void connectToLowerPeers(){
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
    
    private void startDataUnchoker(){
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
    }
    
    private void startRandomUnchoker(){
        new Thread("Random Unchoker") {
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
    
    private void startShutdownThread(){
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
    }
}
