package com.networking.misc;

import java.util.*;

public class Logger {
    public static final Logger INSTANCE = new Logger();

    private Logger() { }

    public synchronized void print(String str) {
        System.out.print("[" + new Date().toString() + "]: " + str);
    }

    public synchronized void println(String str) {
        print(str + "\n");
    }
}
