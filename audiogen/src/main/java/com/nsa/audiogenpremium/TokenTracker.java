package com.nsa.audiogenpremium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TokenTracker {

    // =========================================================================
    // Model limits
    // =========================================================================
    public record ModelLimits(int rpm, int rpd, long tpmLimit, String notes) {
    }

    public static final Map<String, ModelLimits> LIMITS = new LinkedHashMap<>();
    static {
        LIMITS.put("gemini-2.5-pro", new ModelLimits(5, 100, 250_000L, "Best quality"));
        LIMITS.put("gemini-2.5-flash", new ModelLimits(10, 250, 250_000L, "Best balance"));
        LIMITS.put("gemini-2.5-flash-lite", new ModelLimits(15, 1000, 250_000L, "High quota"));
        LIMITS.put("gemini-2.0-flash", new ModelLimits(15, 1500, 250_000L, "Very high RPD"));
        LIMITS.put("gemini-2.0-flash-8b", new ModelLimits(15, 1500, 250_000L, "Highest throughput"));
        LIMITS.put("gemini-2.5-flash-preview-tts", new ModelLimits(10, 100, 250_000L, "TTS audio"));
    }

    // =========================================================================
    // Per-model usage — persisted to disk
    // =========================================================================
    public static class ModelUsage {
        // RPD — resets midnight Pacific
        public int requestsToday = 0;
        public String dayKey = todayKey();

        // RPM + TPM — both reset every 60 seconds (rolling window)
        public int requestsThisMin = 0;
        public long tokensThisMin = 0;
        public long minuteWindowMs = 0;

        // Informational totals
        public long tokensToday = 0; // resets with dayKey, info only
        public long totalTokensEver = 0; // never resets

        public void record(int tokens) {
            long now = System.currentTimeMillis();

            // ── Roll minute window (RPM + TPM reset every 60s) ───────────────
            if (now - minuteWindowMs > 60_000) {
                requestsThisMin = 0;
                tokensThisMin = 0;
                minuteWindowMs = now;
            }

            // ── Roll day window (RPD resets midnight Pacific) ─────────────────
            String today = todayKey();
            if (!today.equals(dayKey)) {
                requestsToday = 0;
                tokensToday = 0;
                dayKey = today;
            }

            requestsToday++;
            requestsThisMin++;
            tokensThisMin += tokens;
            tokensToday += tokens;
            totalTokensEver += tokens;
        }

        private static String todayKey() {
            return LocalDate.now(ZoneId.of("America/Los_Angeles"))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
    }

    // =========================================================================
    // Singleton — FILE must be declared before INSTANCE
    // =========================================================================
    private static final Path FILE = Path.of("token_usage.json");
    private static final TokenTracker INSTANCE = new TokenTracker();

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ModelUsage> usageMap = new ConcurrentHashMap<>();

    private TokenTracker() {
        load();
    }

    public static TokenTracker get() {
        return INSTANCE;
    }

    // =========================================================================
    // Record a completed API request
    // =========================================================================
    public void recordRequest(String modelApiName, int tokens) {
        usageMap.computeIfAbsent(modelApiName, k -> new ModelUsage()).record(tokens);
        save();
    }

    // =========================================================================
    // Getters
    // =========================================================================
    public ModelUsage getUsage(String model) {
        return usageMap.computeIfAbsent(model, k -> new ModelUsage());
    }

    // ── RPD: remaining requests today (resets midnight PT) ───────────────────
    public int remainingRpd(String model) {
        ModelLimits l = LIMITS.get(model);
        if (l == null)
            return 0;
        return Math.max(0, l.rpd() - getUsage(model).requestsToday);
    }

    // ── RPM: remaining requests in current 60s window ────────────────────────
    public int remainingRpm(String model) {
        ModelLimits l = LIMITS.get(model);
        if (l == null)
            return 0;
        ModelUsage u = getUsage(model);
        if (System.currentTimeMillis() - u.minuteWindowMs > 60_000)
            return l.rpm();
        return Math.max(0, l.rpm() - u.requestsThisMin);
    }

    // ── TPM: remaining tokens in current 60s window ──────────────────────────
    public long remainingTpmThisMinute(String model) {
        ModelLimits l = LIMITS.get(model);
        if (l == null)
            return 0;
        ModelUsage u = getUsage(model);
        if (System.currentTimeMillis() - u.minuteWindowMs > 60_000)
            return l.tpmLimit();
        return Math.max(0, l.tpmLimit() - u.tokensThisMin);
    }

    // ── Seconds until current RPM/TPM minute window resets ───────────────────
    public long secondsUntilMinuteReset(String model) {
        ModelUsage u = getUsage(model);
        if (u.minuteWindowMs == 0)
            return 0;
        long elapsed = System.currentTimeMillis() - u.minuteWindowMs;
        return Math.max(0, 60 - elapsed / 1000);
    }

    // ── Seconds until midnight Pacific (RPD reset) ────────────────────────────
    public long secondsUntilDayReset() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Los_Angeles"));
        ZonedDateTime midnight = now.toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.of("America/Los_Angeles"));
        return Duration.between(now, midnight).getSeconds();
    }

    public String formatTimeUntilDayReset() {
        long secs = secondsUntilDayReset();
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        return String.format("%02dh %02dm %02ds", h, m, s);
    }

    // ── Estimate how many tasks fit within BOTH RPD and TPM constraints ───────
    // avgTokensPerTask: rough token cost per API call for this task type
    public int estimateTasks(String model, int avgTokensPerTask) {
        int rpdLeft = remainingRpd(model);
        long tpmLeft = remainingTpmThisMinute(model);
        int byTokens = avgTokensPerTask > 0 ? (int) (tpmLeft / avgTokensPerTask) : rpdLeft;
        // TPM resets every minute, so token constraint is mostly RPM-speed throttle.
        // For "how many total today" the RPD is the real ceiling.
        return Math.min(byTokens > 0 ? byTokens : rpdLeft, rpdLeft);
    }

    // =========================================================================
    // Persist to disk
    // =========================================================================
    private void save() {
        try {
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(FILE.toFile(), usageMap);
        } catch (IOException ignored) {
        }
    }

    private void load() {
        if (!Files.exists(FILE))
            return;
        try {
            Map<String, ModelUsage> loaded = new ObjectMapper().readValue(
                    FILE.toFile(), new TypeReference<Map<String, ModelUsage>>() {
                    });
            usageMap.putAll(loaded);
        } catch (IOException ignored) {
        }
    }

    // =========================================================================
    // Reset helpers (for testing / manual reset)
    // =========================================================================
    public void resetAll() {
        usageMap.clear();
        save();
    }

    public void resetModel(String model) {
        usageMap.remove(model);
        save();
    }
}