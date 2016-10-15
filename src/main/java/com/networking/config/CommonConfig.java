package com.networking.config;

import java.io.*;
import java.util.*;

public class CommonConfig {

    private static final String CFG_FILE_PATH = "/Common.cfg";
    private static final String PREF_NEIGHBORS = "NumberOfPreferredNeighbors";
    private static final String DATA_UNCHOKE = "UnchokingInterval";
    private static final String RANDOM_UNCHOKE = "OptimisticUnchokingInterval";
    private static final String PIECE_SIZE = "PieceSize";
    private static final String FILE = "FileName";
    private static final String FILE_SIZE = "FileSize";

    private static String fileName;
    private static int prefCount, dataUnchoke, randomUnchoke, pieceSize, fileSize;
    private static byte[] file = null;

    static {
        Scanner sc =
            new Scanner(CommonConfig.class.getResourceAsStream(CFG_FILE_PATH));
        while (sc.hasNext()) {
            String key = sc.next();
            String value = sc.next();
            if (key.equals(PREF_NEIGHBORS)) {
                prefCount = Integer.parseInt(value);
            } else if (key.equals(DATA_UNCHOKE)) {
                dataUnchoke = Integer.parseInt(value);
            } else if (key.equals(RANDOM_UNCHOKE)) {
                randomUnchoke = Integer.parseInt(value);
            } else if (key.equals(PIECE_SIZE)) {
                pieceSize = Integer.parseInt(value);
            } else if (key.equals(FILE_SIZE)) {
                fileSize = Integer.parseInt(value);
            } else if (key.equals(FILE)) {
                fileName = value;
            }
        }
    }

    private CommonConfig() { }

    public static int getPreferredCount() {
        return prefCount;
    }

    public static int getDataUnchokeInterval() {
        return dataUnchoke;
    }

    public static int getRandomUnchokeInterval() {
        return randomUnchoke;
    }

    public static byte[] getFile() {
        // only read the file once
        if (file == null) {
            try {
                InputStream is = CommonConfig.class.getResourceAsStream("/" + fileName);
                file = new byte[fileSize];
                is.read(file);
            } catch (IOException ex) { }
        }
        return file;
    }

    public static int getFileSize() {
        return fileSize;
    }

    public static int getNumFilePieces() {
        return (int) Math.ceil(1.*getFileSize()/getPieceSize());
    }

    public static int getPieceSize() {
        return pieceSize;
    }
}
