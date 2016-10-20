package com.networking;

import com.networking.config.*;
import com.networking.misc.*;
import com.networking.model.*;

/**
 * Starts up a BitTorrent client with the given peer id.
 */
public class Bootstrap {
    public static final boolean DEBUG = false;

    public static void main(String[] args) {
        // Look through the peer configs for our config
        PeerConfig clientCfg = null;
        int peerId = -1;
        try {
            peerId = Integer.parseInt(args[0]);
            for (PeerConfig cfg : PeerConfig.PEER_CONFIGS) {
                /* If we find it, remember it and break */
                if (cfg.getPeerID() == peerId) {
                    clientCfg = cfg;
                    break;
                }
            }
            /* If we didn't find it, terminate with exception */
            if (clientCfg == null) throw new Exception();
        } catch (Exception ex) {
            System.out.println("Example Usage: <run program> [peerId]");
            Bootstrap.stackExit(ex);
        }
        // Give the logger our id for log file creation
        Logger.INSTANCE.giveID(peerId);

        // Create and run the client with its config
        Client cl = new Client(clientCfg);
        cl.run();
    }

    public static void stackExit(Exception ex) {
        if (DEBUG) ex.printStackTrace();
        System.exit(1);
    }
}
