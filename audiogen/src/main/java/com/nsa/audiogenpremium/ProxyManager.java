package com.nsa.audiogenpremium;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyManager {

    private static final Path PROXY_FILE = Path.of("proxies.txt");
    private static final Path STATUS_FILE = Path.of("proxy_status.txt");

    // In-memory status map — "green" or "red"
    private static final Map<String, String> statusMap = new ConcurrentHashMap<>();

    static {
        loadStatuses();
    }

    // ── Load plain proxy list ──────────────────────────────────────────────
    public static List<String> load() {
        try {
            if (!Files.exists(PROXY_FILE))
                return new ArrayList<>();
            return new ArrayList<>(Files.readAllLines(PROXY_FILE).stream()
                    .map(String::trim)
                    .filter(l -> !l.isBlank())
                    .toList());
        } catch (IOException e) {
            AppLogger.error("Could not load proxies: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static void save(List<String> proxies) {
        try {
            Files.writeString(PROXY_FILE, String.join("\n", proxies));
            AppLogger.success("Proxies saved (" + proxies.size() + " entries)");
        } catch (IOException e) {
            AppLogger.error("Could not save proxies: " + e.getMessage());
        }
    }

    // ── Status ─────────────────────────────────────────────────────────────
    public static String getStatus(String proxy) {
        return statusMap.getOrDefault(proxy, "unknown");
    }

    public static void setStatus(String proxy, String status) {
        statusMap.put(proxy, status);
        saveStatuses();
    }

    public static void clearStatuses() {
        statusMap.clear();
        saveStatuses();
    }

    private static void loadStatuses() {
        try {
            if (!Files.exists(STATUS_FILE))
                return;
            for (String line : Files.readAllLines(STATUS_FILE)) {
                String[] parts = line.split("\\|", 2);
                if (parts.length == 2)
                    statusMap.put(parts[0].trim(), parts[1].trim());
            }
        } catch (IOException ignored) {
        }
    }

    private static void saveStatuses() {
        try {
            StringBuilder sb = new StringBuilder();
            statusMap.forEach((k, v) -> sb.append(k).append("|").append(v).append("\n"));
            Files.writeString(STATUS_FILE, sb.toString());
        } catch (IOException ignored) {
        }
    }
}