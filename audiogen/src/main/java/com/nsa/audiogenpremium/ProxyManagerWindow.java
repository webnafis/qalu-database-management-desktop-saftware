package com.nsa.audiogenpremium;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.List;

public class ProxyManagerWindow {

    private static Stage existingStage = null;
    private static ListView<String> listView = null;

    // Called from LahajatiService after status update
    public static void refresh() {
        Platform.runLater(() -> {
            if (listView != null)
                listView.refresh();
        });
    }

    public static void open() {
        if (existingStage != null && existingStage.isShowing()) {
            existingStage.toFront();
            return;
        }

        ObservableList<String> proxies = FXCollections.observableArrayList(ProxyManager.load());

        listView = new ListView<>(proxies);
        listView.setEditable(false);
        listView.setPrefHeight(300);
        VBox.setVgrow(listView, Priority.ALWAYS);

        // ── Colored cells ──────────────────────────────────────────────────
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String proxy, boolean empty) {
                super.updateItem(proxy, empty);
                if (empty || proxy == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                String status = ProxyManager.getStatus(proxy);
                String icon = switch (status) {
                    case "green" -> "🟢 ";
                    case "red" -> "🔴 ";
                    default -> "⚪ ";
                };
                String color = switch (status) {
                    case "green" -> "-fx-background-color: #eaffea;";
                    case "red" -> "-fx-background-color: #ffeaea;";
                    default -> "";
                };
                setText(icon + proxy);
                setStyle(color + "-fx-padding: 4 8 4 8;");
            }
        });

        // ── Input row ──────────────────────────────────────────────────────
        TextField inputField = new TextField();
        inputField.setPromptText("e.g. 103.30.28.38:20326");
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button addBtn = new Button("➕ Add");
        addBtn.setOnAction(e -> {
            String text = inputField.getText().trim();
            if (!text.isBlank() && !proxies.contains(text)) {
                proxies.add(text);
                inputField.clear();
            }
        });
        inputField.setOnAction(e -> addBtn.fire());

        HBox inputRow = new HBox(6, inputField, addBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        // ── Bulk paste ─────────────────────────────────────────────────────
        TextArea pasteArea = new TextArea();
        pasteArea.setPromptText("Paste multiple proxies here (one per line), then click Import");
        pasteArea.setPrefRowCount(4);
        pasteArea.setWrapText(false);

        Button importBtn = new Button("📋 Import All");
        importBtn.setOnAction(e -> {
            int added = 0;
            for (String line : pasteArea.getText().split("\n")) {
                String proxy = line.trim();
                if (!proxy.isBlank() && !proxies.contains(proxy)) {
                    proxies.add(proxy);
                    added++;
                }
            }
            pasteArea.clear();
            AppLogger.info("Imported " + added + " proxies");
        });

        // ── Edit ───────────────────────────────────────────────────────────
        Button editBtn = new Button("✏ Edit");
        editBtn.setOnAction(e -> {
            int idx = listView.getSelectionModel().getSelectedIndex();
            if (idx < 0)
                return;
            TextInputDialog dialog = new TextInputDialog(proxies.get(idx));
            dialog.setTitle("Edit Proxy");
            dialog.setHeaderText(null);
            dialog.setContentText("Proxy (host:port):");
            dialog.showAndWait().ifPresent(val -> {
                if (!val.isBlank())
                    proxies.set(idx, val.trim());
            });
        });

        // ── Delete selected ────────────────────────────────────────────────
        Button deleteBtn = new Button("🗑 Delete");
        deleteBtn.setOnAction(e -> {
            int idx = listView.getSelectionModel().getSelectedIndex();
            if (idx >= 0)
                proxies.remove(idx);
        });

        // ── Delete all red ─────────────────────────────────────────────────
        Button deleteRedBtn = new Button("🗑 Delete Red");
        deleteRedBtn.setStyle("-fx-text-fill: #c0392b;");
        deleteRedBtn.setOnAction(e -> {
            List<String> redProxies = proxies.stream()
                    .filter(p -> "red".equals(ProxyManager.getStatus(p)))
                    .toList();
            if (redProxies.isEmpty()) {
                AppLogger.info("No red proxies to delete");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + redProxies.size() + " red (failed) proxies?",
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    proxies.removeAll(redProxies);
                    redProxies.forEach(p -> ProxyManager.setStatus(p, "unknown"));
                    ProxyManager.save(new java.util.ArrayList<>(proxies));
                    AppLogger.success("Deleted " + redProxies.size() + " red proxies");
                    listView.refresh();
                }
            });
        });

        // ── Clear all ──────────────────────────────────────────────────────
        Button clearBtn = new Button("❌ Clear All");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete all " + proxies.size() + " proxies?",
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES)
                    proxies.clear();
            });
        });

        // ── Reset statuses ─────────────────────────────────────────────────
        Button resetStatusBtn = new Button("🔄 Reset Colors");
        resetStatusBtn.setOnAction(e -> {
            ProxyManager.clearStatuses();
            listView.refresh();
            AppLogger.info("Proxy statuses reset");
        });

        // ── Save ───────────────────────────────────────────────────────────
        Button saveBtn = new Button("💾 Save");
        saveBtn.setStyle("-fx-font-weight: bold;");
        saveBtn.setOnAction(e -> {
            ProxyManager.save(new java.util.ArrayList<>(proxies));
            saveBtn.setText("✅ Saved!");
            saveBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-font-weight: bold;");
            new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.seconds(2), ev -> {
                        saveBtn.setText("💾 Save");
                        saveBtn.setStyle("-fx-font-weight: bold;");
                    })).play();
        });

        // ── Count label ────────────────────────────────────────────────────
        Label countLabel = new Label();
        proxies.addListener(
                (javafx.collections.ListChangeListener<String>) c -> countLabel.setText(proxies.size() + " proxies"));
        countLabel.setText(proxies.size() + " proxies");
        countLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        // ── Action row ─────────────────────────────────────────────────────
        HBox actionRow = new HBox(6,
                editBtn, deleteBtn, deleteRedBtn, clearBtn, resetStatusBtn,
                new Region(), countLabel, saveBtn);
        HBox.setHgrow(actionRow.getChildren().get(5), Priority.ALWAYS);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        // ── Legend ─────────────────────────────────────────────────────────
        Label legend = new Label("🟢 Connected   🔴 Failed   ⚪ Untested");
        legend.setStyle("-fx-font-size: 10; -fx-text-fill: #888;");

        // ── Layout ─────────────────────────────────────────────────────────
        Label title = new Label("🌐 Proxy List");
        title.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");

        VBox root = new VBox(10,
                title,
                legend,
                inputRow,
                listView,
                actionRow,
                new Separator(),
                new Label("Bulk import:"),
                pasteArea,
                importBtn);
        root.setPadding(new Insets(14));

        Stage stage = new Stage();
        stage.setTitle("Proxy Manager");
        stage.setScene(new Scene(root, 440, 620));
        stage.setOnCloseRequest(e -> {
            existingStage = null;
            listView = null;
        });
        existingStage = stage;
        stage.show();
    }
}