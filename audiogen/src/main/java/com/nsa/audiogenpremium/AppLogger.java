package com.nsa.audiogenpremium;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AppLogger {

    public enum Level {
        INFO, SUCCESS, WARN, ERROR
    }

    public record LogEntry(String timestamp, Level level, String message) {
        public String display() {
            return "[" + timestamp + "] [" + level + "] " + message;
        }
    }

    private static final AppLogger INSTANCE = new AppLogger();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_ENTRIES = 500;

    private final ObservableList<LogEntry> entries = FXCollections.observableArrayList();

    private AppLogger() {
    }

    public static AppLogger get() {
        return INSTANCE;
    }

    public ObservableList<LogEntry> getEntries() {
        return entries;
    }

    public static void info(String msg) {
        INSTANCE.log(Level.INFO, msg);
    }

    public static void success(String msg) {
        INSTANCE.log(Level.SUCCESS, msg);
    }

    public static void warn(String msg) {
        INSTANCE.log(Level.WARN, msg);
    }

    public static void error(String msg) {
        INSTANCE.log(Level.ERROR, msg);
    }

    private void log(Level level, String message) {
        String ts = LocalDateTime.now().format(FMT);
        LogEntry entry = new LogEntry(ts, level, message);
        System.out.println(entry.display()); // still print to console
        Platform.runLater(() -> {
            entries.add(entry);
            if (entries.size() > MAX_ENTRIES)
                entries.remove(0);
        });
    }
}