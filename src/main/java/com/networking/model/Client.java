package com.networking.model;

import com.networking.config.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Does all of the work for managing a single instance of the bittorrent client.
 */
public class Client implements Runnable {

    private final PeerConfig clientCfg;
    private final ArrayList<Peer> peers = new ArrayList<Peer>();
    // bit field
    // lock for bit field?
    // etc

    public Client(PeerConfig clientCfg) {
        this.clientCfg = clientCfg;
        // initialize bitfield
    }

    public int getClientID() {
        return clientCfg.getPeerID();
    }

    public synchronized void dataUnchoke() {
        // will be called by a timer asynchronously
    }

    public synchronized void randomUnchoke() {
        // will be called by a timer asynchronously
    }

    public synchronized void addPeer(Peer peer) {
        peers.add(peer);
    }

    private synchronized void connect(PeerConfig pConfig) throws IOException {
        // open socket to pConfig.
        Socket socket = new Socket(pConfig.getHost(), pConfig.getPort());
        addPeer(new Peer(socket, this));
    }

    public void run() {
        System.out.println("Starting client with ID <" + getClientID() + "> on port <" + clientCfg.getPort() + ">");
        // connect to lower peers
        for (PeerConfig pConfig : PeerConfig.PEER_CONFIGS) {
            if (pConfig.getPeerID() < clientCfg.getPeerID()) {
                try {
                    connect(pConfig);
                } catch (IOException ex) {
                    // fatally exit if we can't connect to a prior peer
                    System.out.println("Error connecting to peer <" + pConfig.getPeerID() + ">, terminating.");
                    System.exit(1);
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
        new Thread("Random Unchoker") {
            long lastChoke = 0;
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
        // listen for higher peers
        try {
            ServerSocket server = new ServerSocket(clientCfg.getPort());
            do {
                Socket socket = server.accept();
                addPeer(new Peer(socket, this));
            } while(true);
        } catch (Exception ex) {
            System.out.println("Error running server socket, terminating.");
            System.exit(1);
        }
    }
}
