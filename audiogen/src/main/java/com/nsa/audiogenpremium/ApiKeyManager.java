package com.nsa.audiogenpremium;

import java.io.*;
import java.nio.file.*;

public class ApiKeyManager {

    private static final Path KEY_FILE = Path.of("gemini.key");

    public static String load() {
        try {
            if (Files.exists(KEY_FILE))
                return Files.readString(KEY_FILE).trim();
        } catch (IOException ignored) {
        }
        return "";
    }

    public static void save(String key) {
        try {
            Files.writeString(KEY_FILE, key.trim());
        } catch (IOException e) {
            System.err.println("Could not save API key: " + e.getMessage());
        }
    }
}