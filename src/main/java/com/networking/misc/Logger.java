package com.networking.misc;

import com.networking.Bootstrap;

import java.io.*;
import java.util.*;

public class Logger {
    public static final Logger INSTANCE = new Logger();
    private PrintWriter outstream = new PrintWriter(System.out);

    private Logger() { }

    public synchronized void print(String str) {
        outstream.print("[" + new Date().toString() + "]: " + str);
        outstream.flush();
    }

    public synchronized void println(String str) {
        print(str + System.lineSeparator());
    }

    public synchronized void giveID(int id) {
        if (outstream != null) {
            outstream.close();
        }
    	try {
    		outstream = new PrintWriter(new FileWriter("./log_peer_" + id + ".log"));
    	} catch (IOException ex) {
    		Bootstrap.stackExit(ex);
        }
    }
}
