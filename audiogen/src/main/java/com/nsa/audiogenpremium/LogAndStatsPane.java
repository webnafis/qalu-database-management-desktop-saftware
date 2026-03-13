package com.nsa.audiogenpremium;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.*;
import javafx.util.Duration;

public class LogAndStatsPane extends VBox {

    private final VBox statsContainer = new VBox(12);
    private final Label countdownLabel = new Label();
    private Timeline refreshTimeline;

    public LogAndStatsPane() {
        setSpacing(0);
        setPrefSize(700, 620);

        // ── Tab pane: Logs | Token Stats ──────────────────────────────────────
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        tabs.getTabs().addAll(buildLogsTab(), buildStatsTab());
        getChildren().add(tabs);

        // Refresh stats every second (for live countdown)
        refreshTimeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> refreshStats()));
        refreshTimeline.setCycleCount(Animation.INDEFINITE);
        refreshTimeline.play();
    }

    // =========================================================================
    // Logs tab
    // =========================================================================
    private Tab buildLogsTab() {
        ListView<AppLogger.LogEntry> logList = new ListView<>(AppLogger.get().getEntries());
        logList.setCellFactory(lv -> new LogCell());
        logList.setStyle(
                "-fx-background-color: #1e1e1e; "
                        + "-fx-control-inner-background: #1e1e1e;");
        VBox.setVgrow(logList, Priority.ALWAYS);

        // Auto-scroll to newest entry
        AppLogger.get().getEntries().addListener(
                (ListChangeListener<AppLogger.LogEntry>) c -> Platform.runLater(() -> {
                    if (!logList.getItems().isEmpty())
                        logList.scrollTo(logList.getItems().size() - 1);
                }));

        Button clearBtn = new Button("🗑 Clear Logs");
        Label countLabel = new Label();
        AppLogger.get().getEntries().addListener(
                (ListChangeListener<AppLogger.LogEntry>) c -> Platform.runLater(() -> countLabel.setText(
                        logList.getItems().size() + " entries")));
        countLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
        clearBtn.setOnAction(e -> AppLogger.get().getEntries().clear());

        HBox toolbar = new HBox(10, clearBtn, new Region(), countLabel);
        HBox.setHgrow(toolbar.getChildren().get(1), Priority.ALWAYS);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #2d2d2d;");

        VBox container = new VBox(toolbar, logList);
        VBox.setVgrow(logList, Priority.ALWAYS);
        container.setStyle("-fx-background-color: #1e1e1e;");

        Tab tab = new Tab("📋 Logs", container);
        return tab;
    }

    // =========================================================================
    // Stats tab
    // =========================================================================
    private Tab buildStatsTab() {
        // Countdown header
        countdownLabel.setStyle(
                "-fx-font-size: 18; -fx-font-weight: bold; "
                        + "-fx-text-fill: #e67e22; -fx-padding: 0 0 4 0;");

        Label resetTitle = new Label("⏰ Time Until Daily Quota Reset (Midnight PT):");
        resetTitle.setStyle("-fx-font-size: 12; -fx-text-fill: #aaa;");

        VBox header = new VBox(4, resetTitle, countdownLabel);
        header.setPadding(new Insets(12, 14, 8, 14));
        header.setStyle("-fx-background-color: #2d2d2d;");

        // Model cards
        ScrollPane scroll = new ScrollPane(statsContainer);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: #1e1e1e; "
                + "-fx-background: #1e1e1e;");
        statsContainer.setPadding(new Insets(10));
        statsContainer.setStyle("-fx-background-color: #1e1e1e;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        refreshStats();

        VBox root = new VBox(header, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.setStyle("-fx-background-color: #1e1e1e;");

        return new Tab("⚡ Token Stats", root);
    }

    // =========================================================================
    // Refresh all model cards
    // =========================================================================
    private void refreshStats() {
        countdownLabel.setText(TokenTracker.get().formatTimeUntilDayReset());
        statsContainer.getChildren().clear();

        // Text models
        for (GeminiService.TextModel m : GeminiService.TextModel.values()) {
            statsContainer.getChildren().add(
                    buildModelCard(m.apiName, m.displayName, false));
        }
        // TTS model
        statsContainer.getChildren().add(
                buildModelCard("gemini-2.5-flash-preview-tts",
                        "TTS (Audio Generation)", true));
    }

    // =========================================================================
    // One card per model
    // =========================================================================
    private VBox buildModelCard(String apiName, String displayName, boolean isTts) {
        TokenTracker.ModelUsage u = TokenTracker.get().getUsage(apiName);
        TokenTracker.ModelLimits l = TokenTracker.LIMITS.get(apiName);

        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color: #252525; -fx-background-radius: 8; "
                + "-fx-border-color: #3a3a3a; -fx-border-radius: 8;");

        if (l == null) {
            card.getChildren().add(label(displayName, "#ddd", 13, true));
            return card;
        }

        Label title = label(displayName, "#ffffff", 13, true);
        Label model = label(apiName, "#666", 10, false);
        Label notes = label("ℹ " + l.notes(), "#5dade2", 10, false);
        VBox titleBox = new VBox(2, title, model, notes);

        // ── RPD — resets midnight Pacific ────────────────────────────────────────
        int rpdUsed = u.requestsToday;
        int rpdMax = l.rpd();
        int rpdLeft = TokenTracker.get().remainingRpd(apiName);
        double rpdPct = rpdMax > 0 ? (double) rpdUsed / rpdMax : 0;
        ProgressBar rpdBar = progressBar(rpdPct);
        Label rpdDetail = label(
                "Requests today:  " + rpdUsed + " / " + rpdMax
                        + "   |   " + rpdLeft + " left  (resets midnight PT)",
                barColor(rpdPct), 11, false);

        // ── RPM — resets every minute ─────────────────────────────────────────────
        int rpmUsed = u.requestsThisMin;
        int rpmMax = l.rpm();
        int rpmLeft = TokenTracker.get().remainingRpm(apiName);
        long rpmSecs = TokenTracker.get().secondsUntilMinuteReset(apiName);
        double rpmPct = rpmMax > 0 ? (double) rpmUsed / rpmMax : 0;
        ProgressBar rpmBar = progressBar(rpmPct);
        Label rpmDetail = label(
                "Requests/min:    " + rpmUsed + " / " + rpmMax
                        + "   |   " + rpmLeft + " left  (resets in " + rpmSecs + "s)",
                barColor(rpmPct), 11, false);

        // ── TPM — ALSO resets every minute (not per day!) ─────────────────────────
        long tpmUsed = u.tokensThisMin;
        long tpmMax = l.tpmLimit();
        long tpmLeft = TokenTracker.get().remainingTpmThisMinute(apiName);
        double tpmPct = tpmMax > 0 ? (double) tpmUsed / tpmMax : 0;
        ProgressBar tpmBar = progressBar(tpmPct);
        Label tpmDetail = label(
                "Tokens this min: " + fmtNum(tpmUsed) + " / " + fmtNum(tpmMax)
                        + "   |   " + fmtNum(tpmLeft) + " left  (resets in " + rpmSecs + "s)",
                barColor(tpmPct), 11, false);

        // ── Informational daily totals ────────────────────────────────────────────
        Label dayTokens = label(
                "Tokens used today (info only): " + fmtNum(u.tokensToday),
                "#777", 10, false);
        Label totalLabel = label(
                "All-time tokens: " + fmtNum(u.totalTokensEver),
                "#555", 10, false);

        // ── What can I do ─────────────────────────────────────────────────────────
        VBox canDoBox = buildCanDo(apiName, rpdLeft, tpmLeft, isTts);

        card.getChildren().addAll(
                titleBox,
                sep(),
                rowLabel("📅 Daily Requests (resets midnight PT)"), rpdBar, rpdDetail,
                rowLabel("⚡ Requests/Min  (resets every 60s)"), rpmBar, rpmDetail,
                rowLabel("🔤 Tokens/Min    (resets every 60s)"), tpmBar, tpmDetail,
                sep(),
                dayTokens, totalLabel,
                sep(),
                canDoBox);

        return card;
    }

    // =========================================================================
    // "What can I do" estimate box
    // =========================================================================
    private VBox buildCanDo(String apiName, int rpdLeft, long tokLeft, boolean isTts) {
        VBox box = new VBox(5);
        Label title = label("🔮 With remaining quota you can approximately:", "#aaa", 11, true);
        box.getChildren().add(title);

        if (rpdLeft <= 0) {
            box.getChildren().add(label("  ❌ Daily request limit reached — resets in "
                    + TokenTracker.get().formatTimeUntilDayReset(), "#e74c3c", 11, false));
            return box;
        }

        if (isTts) {
            // TTS: ~50 tokens per word audio
            int byToken = tokLeft > 0 ? (int) (tokLeft / 50) : rpdLeft;
            int possible = Math.min(byToken, rpdLeft);
            box.getChildren().addAll(
                    canDoRow("🔊 Arabic word audios", possible),
                    canDoRow("📖 Full pages (20 words each)", possible / 20));
        } else {
            // Text models: approximate token costs per task
            int wordExtractions = estimate(rpdLeft, tokLeft, 4000); // PDF page ~4k tokens
            int partSplits = estimate(rpdLeft, tokLeft, 300); // single word ~300 tokens
            int verifications = estimate(rpdLeft, tokLeft, 4500); // PDF + verify ~4.5k tokens
            int pageExtracts = estimate(rpdLeft, tokLeft, 4000);

            box.getChildren().addAll(
                    canDoRow("📄 PDF page extractions", pageExtracts),
                    canDoRow("✂  Arabic word part splits", partSplits),
                    canDoRow("🔍 Word verifications (with PDF)", verifications),
                    canDoRow("📝 Requests at 1k tokens each", estimate(rpdLeft, tokLeft, 1000)));
        }

        return box;
    }

    private int estimate(int rpdLeft, long tokLeft, int avgCost) {
        int byTokens = avgCost > 0 ? (int) (tokLeft / avgCost) : rpdLeft;
        return Math.min(byTokens, rpdLeft);
    }

    private HBox canDoRow(String label, int count) {
        String color = count > 10 ? "#2ecc71" : count > 0 ? "#f39c12" : "#e74c3c";
        Label lbl = label("  " + label + ":", "#ccc", 11, false);
        Label val = label("~" + fmtNum(count), color, 12, true);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(6, lbl, gap, val);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private ProgressBar progressBar(double pct) {
        ProgressBar bar = new ProgressBar(pct);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(10);
        bar.setStyle("-fx-accent: " + barColor(pct) + ";");
        return bar;
    }

    private String barColor(double pct) {
        if (pct >= 0.85)
            return "#e74c3c";
        if (pct >= 0.60)
            return "#f39c12";
        return "#2ecc71";
    }

    private Label label(String text, String color, int size, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + color + "; "
                + "-fx-font-size: " + size + "; "
                + (bold ? "-fx-font-weight: bold;" : ""));
        l.setWrapText(true);
        return l;
    }

    private Label rowLabel(String text) {
        return label(text, "#999", 10, true);
    }

    private Separator sep() {
        Separator s = new Separator();
        s.setStyle("-fx-background-color: #3a3a3a;");
        return s;
    }

    private String fmtNum(long n) {
        return String.format("%,d", n);
    }

    // =========================================================================
    // Log cell
    // =========================================================================
    private static class LogCell extends ListCell<AppLogger.LogEntry> {
        @Override
        protected void updateItem(AppLogger.LogEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setStyle("-fx-background-color: transparent;");
                return;
            }
            setText(item.display());
            setFont(Font.font("Monospaced", 11));
            String bg = switch (item.level()) {
                case ERROR -> "#2c1515";
                case WARN -> "#2c2515";
                case SUCCESS -> "#152c15";
                default -> "transparent";
            };
            String fg = switch (item.level()) {
                case SUCCESS -> "#2ecc71";
                case WARN -> "#f39c12";
                case ERROR -> "#e74c3c";
                default -> "#cccccc";
            };
            setStyle("-fx-text-fill: " + fg + "; "
                    + "-fx-background-color: " + bg + ";");
        }
    }

    // =========================================================================
    // Open as a window
    // =========================================================================
    public static void openWindow() {
        // Check if already open — bring to front
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
            if (w instanceof Stage s && "Logs & Token Tracker".equals(s.getTitle())) {
                s.toFront();
                return;
            }
        }
        Stage stage = new Stage(StageStyle.DECORATED);
        stage.setTitle("Logs & Token Tracker");
        LogAndStatsPane pane = new LogAndStatsPane();
        stage.setScene(new Scene(pane, 720, 660));
        stage.setAlwaysOnTop(false);
        // Stop the timeline when window closes to avoid memory leak
        stage.setOnCloseRequest(e -> pane.refreshTimeline.stop());
        stage.show();
    }
}