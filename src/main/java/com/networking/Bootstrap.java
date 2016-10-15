package com.networking;

import com.networking.config.*;
import com.networking.model.*;

/**
 * Starts up a BitTorrent client with the given peer id.
 */
public class Bootstrap {
    public static void main(String[] args) {
        int cc = CommonConfig.getPreferredCount();
        PeerConfig clientCfg = null;
        try {
            int peerId = Integer.parseInt(args[0]);
            for (PeerConfig cfg : PeerConfig.PEER_CONFIGS) {
                if (cfg.getPeerID() == peerId) {
                    clientCfg = cfg;
                }
            }
            if (clientCfg == null) throw new Exception();
        } catch (Exception ex) {
            System.out.println("Example Usage: <run program> [peerId]");
            System.exit(1);
        }
        Client cl = new Client(clientCfg);
        cl.run();
    }
}
