package com.networking.misc;

import java.util.*;

public class Logger {
    public static final Logger INSTANCE = new Logger();

    private Logger() { }

    public void print(String str) {
        System.out.print("[" + new Date().toString() + "]: " + str);
    }

    public void println(String str) {
        print(str + "\n");
    }
}
