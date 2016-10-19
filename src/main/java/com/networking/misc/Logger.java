package com.networking.misc;

import com.networking.Bootstrap;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.*;

public class Logger {
    public static final Logger INSTANCE = new Logger();
    private PrintStream outstream;
    
    private Logger() { }

    public synchronized void print(String str) {
        if(outstream == null) Bootstrap.stackExit(new RuntimeException(
            "Printed to logger without first giving it the client id."));
        outstream.print("[" + new Date().toString() + "]: " + str);
    }

    public synchronized void println(String str) {
        print(str + System.lineSeparator());
    }
    
    public synchronized void giveID(int id){
        if(outstream == null){
            try{
                outstream = new PrintStream("./log_peer_"+id+".log");
            }
            catch(FileNotFoundException e){
                Bootstrap.stackExit(e);
            }
        }
    }
}
