package com.networking.config;

import java.util.*;

public class PeerConfig {

    private static final String CFG_FILE_PATH = "/PeerInfo.cfg";
    public static final ArrayList<PeerConfig> PEER_CONFIGS
        = new ArrayList<PeerConfig>();

    static {
        Scanner sc =
            new Scanner(CommonConfig.class.getResourceAsStream(CFG_FILE_PATH));
        while (sc.hasNextLine()) {
            PeerConfig conf = parseConfig(sc.nextLine());
            if (conf == null) continue;
            PEER_CONFIGS.add(conf);
        }
    }

    private int id, port;
    private String host;
    private boolean hasFile;

    private PeerConfig(int id, String host, int port, boolean hasFile) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.hasFile = hasFile;
    }

    public int getPeerID() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean hasFile() {
        return hasFile;
    }

    private static PeerConfig parseConfig(String line) {
        String[] spl = line.split(" ");
        if (spl[0].equals("#")) return null; // commented out
        return new PeerConfig(Integer.parseInt(spl[0]),
            spl[1],
            Integer.parseInt(spl[2]),
            spl[3].equals("1")
        );
    }
}
