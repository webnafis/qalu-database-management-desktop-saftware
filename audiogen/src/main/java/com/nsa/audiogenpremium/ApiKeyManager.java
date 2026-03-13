package com.nsa.audiogenpremium;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class ApiKeyManager {

    private static final Path PREFS_FILE = Path.of("gemini.properties");

    public static String load() {
        return loadProp("apiKey", "");
    }

    public static void save(String key) {
        saveProp("apiKey", key.trim());
    }

    public static GeminiService.TextModel loadTextModel() {
        String name = loadProp("textModel", GeminiService.TextModel.FLASH.name());
        try {
            return GeminiService.TextModel.valueOf(name);
        } catch (Exception e) {
            return GeminiService.TextModel.FLASH;
        }
    }

    public static void saveTextModel(GeminiService.TextModel model) {
        saveProp("textModel", model.name());
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static String loadProp(String key, String defaultValue) {
        try {
            if (!Files.exists(PREFS_FILE))
                return defaultValue;
            Properties p = new Properties();
            try (BufferedReader r = Files.newBufferedReader(PREFS_FILE)) {
                p.load(r);
            }
            return p.getProperty(key, defaultValue);
        } catch (IOException e) {
            return defaultValue;
        }
    }

    private static void saveProp(String key, String value) {
        try {
            Properties p = new Properties();
            if (Files.exists(PREFS_FILE)) {
                try (BufferedReader r = Files.newBufferedReader(PREFS_FILE)) {
                    p.load(r);
                }
            }
            p.setProperty(key, value);
            try (BufferedWriter w = Files.newBufferedWriter(PREFS_FILE)) {
                p.store(w, "Gemini settings");
            }
        } catch (IOException e) {
            System.err.println("Could not save prefs: " + e.getMessage());
        }
    }

    public enum AudioProvider {
        GEMINI, LAHAJATI
    }

    public AudioProvider loadAudioProvider() {
        try {
            return AudioProvider.valueOf(loadProp("audioProvider", "GEMINI"));
        } catch (Exception e) {
            return AudioProvider.GEMINI;
        }
    }

    public void saveAudioProvider(AudioProvider p) {
        saveProp("audioProvider", p.name());
    }
}