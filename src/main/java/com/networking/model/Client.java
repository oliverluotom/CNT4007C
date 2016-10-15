package com.networking.model;

import com.networking.config.*;

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

    public synchronized void addPeer(Peer p) {
        peers.add(p);
    }

    public void run() {
        System.out.println("Starting client with ID <" + getClientID() + "> on port <" + clientCfg.getPort() + ">");
        // start data/random unchoker
        // connect to lower peers
        // listen for higher peers
    }
}
