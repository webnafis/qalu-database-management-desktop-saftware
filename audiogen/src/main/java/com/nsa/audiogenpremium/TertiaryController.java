package com.nsa.audiogenpremium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class TertiaryController {

    @FXML
    private ListView<PageData> pageListView;
    @FXML
    private Label pageInfoLabel;
    @FXML
    private Button prevBtn;
    @FXML
    private Button nextBtn;
    @FXML
    private Label selectedPageLabel;
    @FXML
    private TableView<TertiaryWordEntry> wordsTable;
    @FXML
    private Button saveBtn;

    private static final String JSON_PATH = "output_data.json";
    private final ObjectMapper mapper = new ObjectMapper();

    private final ObservableList<PageData> allPages = FXCollections.observableArrayList();
    private final ObservableList<TertiaryWordEntry> currentEntries = FXCollections.observableArrayList();
    // ── Fields
    // ────────────────────────────────────────────────────────────────────
    private final LahajatiService lahajatiService = new LahajatiService();
    private ApiKeyManager.AudioProvider audioProvider;
    private PageData currentPage = null;

    private enum TaskType {
        IMAGE, AUDIO, ARABIC_PARTS
    }

    // ── Add this field at the top of TertiaryController ──────────────────────────
    private boolean isDirty = false;

    private void markDirty() {
        isDirty = true;
    }

    private void clearDirty() {
        isDirty = false;
    }

    // near: private final LahajatiService lahajatiService = new LahajatiService();
    @FXML
    private HBox toolbarHBox;

    // In controller:
    @FXML
    public void handleOpenProxies() {
        ProxyManagerWindow.open();
    }

    // ── Add field ────────────────────────────────────────────────────────────────
    @FXML
    private ComboBox<GeminiService.TextModel> modelComboBox;
    // =========================================================================
    // Init
    // =========================================================================

    @FXML
    private void switchToSecondary() throws IOException {
        if (!checkUnsaved())
            return;
        App.setRoot("secondary");
    }

    @FXML
    public void initialize() {
        setupPageList();
        setupWordsTable();
        loadData();
        // ── Model ComboBox ─────────────────────────────────────────────────────
        modelComboBox.getItems().addAll(GeminiService.TextModel.values());
        modelComboBox.setValue(ApiKeyManager.loadTextModel());
        modelComboBox.setOnAction(e -> {
            ApiKeyManager.saveTextModel(modelComboBox.getValue());
            AppLogger.info("Text model switched to: " + modelComboBox.getValue());
        });
        // ── Audio provider picker ──────────────────────────────────────────────
        audioProvider = new ApiKeyManager().loadAudioProvider();

        ComboBox<ApiKeyManager.AudioProvider> providerBox = new ComboBox<>(FXCollections.observableArrayList(
                ApiKeyManager.AudioProvider.values()));
        providerBox.setValue(audioProvider);
        providerBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ApiKeyManager.AudioProvider p) {
                return p == ApiKeyManager.AudioProvider.GEMINI ? "🤖 Gemini TTS" : "🌐 Lahajati";
            }

            @Override
            public ApiKeyManager.AudioProvider fromString(String s) {
                return null;
            }
        });
        providerBox.setOnAction(e -> {
            audioProvider = providerBox.getValue();
            new ApiKeyManager().saveAudioProvider(audioProvider);
            AppLogger.info("Audio provider switched to: " + audioProvider);
        });
        providerBox.setTooltip(new Tooltip("Choose audio generation provider"));
        toolbarHBox.getChildren().add(providerBox); // ← appends to toolbar

        // ── Shutdown hook ──────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> lahajatiService.quitDriver()));
    }

    // =========================================================================
    // Page list — no dismiss, just check badge
    // =========================================================================

    private void setupPageList() {
        pageListView.setCellFactory(lv -> new PageCell());
        pageListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> {
                    if (n == null || n == currentPage)
                        return;
                    if (!checkUnsaved()) {
                        // Revert the list selection back to current page without re-triggering
                        Platform.runLater(() -> pageListView.getSelectionModel().select(currentPage));
                        return;
                    }
                    loadPageWords(n);
                });
        pageListView.setItems(allPages);
    }

    // =========================================================================
    // Words table columns
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void setupWordsTable() {
        wordsTable.setEditable(true);
        wordsTable.setItems(currentEntries);
        wordsTable.setFixedCellSize(Region.USE_COMPUTED_SIZE);

        // Select
        TableColumn<TertiaryWordEntry, Boolean> selCol = new TableColumn<>("");
        selCol.setCellValueFactory(d -> d.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setEditable(true);
        selCol.setMinWidth(36);
        selCol.setMaxWidth(36);

        // Arabic (read-only)
        TableColumn<TertiaryWordEntry, String> arabicCol = new TableColumn<>("Arabic");
        arabicCol.setCellValueFactory(d -> d.getValue().arabicProperty());
        arabicCol.setEditable(false);
        arabicCol.setMinWidth(90);

        // Bangla (read-only)
        TableColumn<TertiaryWordEntry, String> banglaCol = new TableColumn<>("Bangla");
        banglaCol.setCellValueFactory(d -> d.getValue().banglaProperty());
        banglaCol.setEditable(false);
        banglaCol.setMinWidth(90);

        // Image
        TableColumn<TertiaryWordEntry, TertiaryWordEntry> imageCol = new TableColumn<>("Image");
        imageCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        imageCol.setCellFactory(c -> new ImageCell());
        imageCol.setMinWidth(150);

        // Audio
        TableColumn<TertiaryWordEntry, TertiaryWordEntry> audioCol = new TableColumn<>("Audio");
        audioCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        audioCol.setCellFactory(c -> new AudioCell());
        audioCol.setMinWidth(150);

        // Arabic Parts (array, no bulk)
        TableColumn<TertiaryWordEntry, TertiaryWordEntry> partsCol = new TableColumn<>("Arabic Parts");
        partsCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        partsCol.setCellFactory(c -> new ArabicPartsCell());
        partsCol.setMinWidth(200);

        // Tertiary Checked
        TableColumn<TertiaryWordEntry, Boolean> checkedCol = new TableColumn<>("✓");
        checkedCol.setCellValueFactory(d -> d.getValue().tertiaryCheckedProperty());
        checkedCol.setCellFactory(CheckBoxTableCell.forTableColumn(checkedCol));
        checkedCol.setEditable(true);
        checkedCol.setMinWidth(46);
        checkedCol.setMaxWidth(46);
        // ── Verify with Gemini column
        // ─────────────────────────────────────────────────
        TableColumn<TertiaryWordEntry, TertiaryWordEntry> verifyCol = new TableColumn<>("Verify");
        verifyCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        verifyCol.setCellFactory(c -> new VerifyCell());
        verifyCol.setMinWidth(90);
        verifyCol.setMaxWidth(90);

        // ── Parts concat check column (auto, no user action needed)
        // ───────────────────
        TableColumn<TertiaryWordEntry, TertiaryWordEntry> partsCheckCol = new TableColumn<>("Parts ✓");
        partsCheckCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue()));
        partsCheckCol.setCellFactory(c -> new PartsCheckCell());
        partsCheckCol.setMinWidth(60);
        partsCheckCol.setMaxWidth(60);

        wordsTable.getColumns().addAll(
                selCol, arabicCol, banglaCol,
                imageCol, audioCol, partsCol,
                checkedCol, verifyCol, partsCheckCol // ← updated
        );
    }

    // =========================================================================
    // Load a page's words into the table
    // =========================================================================

    private void loadPageWords(PageData page) {
        currentPage = page;
        currentEntries.clear();

        selectedPageLabel.setText("Page " + page.getPageNumber()
                + (page.isTertiaryFullyChecked() ? "  ✅" : ""));
        updateNavButtons();

        if (page.getWordsinfo() == null)
            return;

        for (int i = 0; i < page.getWordsinfo().size(); i++) {
            Map<String, String> m = page.getWordsinfo().get(i);

            // Parse arabicParts JSON array stored as string
            List<String> parts = new ArrayList<>();
            String partsJson = m.getOrDefault("arabicParts", "[]");
            try {
                if (partsJson.startsWith("["))
                    parts = mapper.readValue(partsJson, new TypeReference<List<String>>() {
                    });
                else if (!partsJson.isEmpty())
                    parts.add(partsJson); // legacy plain string
            } catch (Exception ignored) {
                if (!partsJson.isEmpty())
                    parts.add(partsJson);
            }

            TertiaryWordEntry entry = new TertiaryWordEntry(
                    i,
                    m.getOrDefault("arabic", ""),
                    m.getOrDefault("bangla", ""),
                    m.getOrDefault("imagePath", ""),
                    m.getOrDefault("audioPath", ""),
                    parts,
                    "true".equalsIgnoreCase(m.getOrDefault("tertiaryChecked", "false")));

            final int idx = i;
            entry.tertiaryCheckedProperty().addListener((obs, o, n) -> {
                markDirty();
                syncEntryToMap(page, idx, entry);
                saveJsonToFile();
                selectedPageLabel.setText("Page " + page.getPageNumber()
                        + (page.isTertiaryFullyChecked() ? "  ✅" : ""));
                pageListView.refresh();
            });

            currentEntries.add(entry);
        }
    }

    private void syncEntryToMap(PageData page, int index, TertiaryWordEntry e) {
        if (page.getWordsinfo() == null || index >= page.getWordsinfo().size())
            return;
        Map<String, String> m = page.getWordsinfo().get(index);
        m.put("imagePath", e.getImagePath());
        m.put("audioPath", e.getAudioPath());
        m.put("tertiaryChecked", String.valueOf(e.isTertiaryChecked()));
        try {
            m.put("arabicParts", mapper.writeValueAsString(new ArrayList<>(e.getArabicParts())));
        } catch (Exception ex) {
            m.put("arabicParts", "[]");
        }
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void updateNavButtons() {
        int idx = allPages.indexOf(currentPage);
        prevBtn.setDisable(idx <= 0);
        nextBtn.setDisable(idx >= allPages.size() - 1);
        pageInfoLabel.setText((idx + 1) + " / " + allPages.size());
    }

    @FXML
    public void handlePrev() {
        if (!checkUnsaved())
            return;
        int idx = allPages.indexOf(currentPage);
        if (idx > 0)
            pageListView.getSelectionModel().select(allPages.get(idx - 1));
    }

    @FXML
    public void handleNext() {
        if (!checkUnsaved())
            return;
        int idx = allPages.indexOf(currentPage);
        if (idx < allPages.size() - 1)
            pageListView.getSelectionModel().select(allPages.get(idx + 1));
    }

    // =========================================================================
    // Bulk word actions (no Arabic Parts bulk)
    // =========================================================================

    @FXML
    public void handleSelectAllWords() {
        boolean allSel = currentEntries.stream().allMatch(TertiaryWordEntry::isSelected);
        currentEntries.forEach(e -> e.setSelected(!allSel));
    }

    @FXML
    public void handleGenerateImages() {
        runSequential(getTargets(), 0, TaskType.IMAGE);
    }

    @FXML
    public void handleGenerateAudio() {
        runSequential(getTargets(), 0, TaskType.AUDIO);
    }

    @FXML
    public void handleCheckAllWords() {
        currentEntries.forEach(e -> e.setTertiaryChecked(true));
        if (currentPage != null) {
            for (int i = 0; i < currentEntries.size(); i++)
                syncEntryToMap(currentPage, i, currentEntries.get(i));
            saveJsonToFile();
        }
        selectedPageLabel.setText("Page " + currentPage.getPageNumber()
                + (currentPage.isTertiaryFullyChecked() ? "  ✅" : ""));
        pageListView.refresh();
    }

    @FXML
    public void handleSave() {
        if (currentPage == null)
            return;
        for (int i = 0; i < currentEntries.size(); i++)
            syncEntryToMap(currentPage, i, currentEntries.get(i));
        saveJsonToFile();
        clearDirty();
        flashSaveButton();
    }

    private boolean checkUnsaved() {
        if (!isDirty)
            return true;

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes.");
        alert.setContentText("Do you want to save before leaving?");

        ButtonType btnSave = new ButtonType("Save & Continue", ButtonBar.ButtonData.YES);
        ButtonType btnDiscard = new ButtonType("Discard & Continue", ButtonBar.ButtonData.NO);
        ButtonType btnCancel = new ButtonType("Stay Here", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(btnSave, btnDiscard, btnCancel);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == btnCancel)
            return false;
        if (result.get() == btnSave)
            handleSave();
        else
            clearDirty();
        return true;
    }

    private void flashSaveButton() {
        saveBtn.setText("✅ Saved!");
        saveBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-font-weight: bold;");
        new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            saveBtn.setText("💾 Save");
            saveBtn.setStyle("");
        })).play();
    }

    private List<TertiaryWordEntry> getTargets() {
        List<TertiaryWordEntry> sel = currentEntries.stream()
                .filter(TertiaryWordEntry::isSelected).toList();
        return sel.isEmpty() ? new ArrayList<>(currentEntries) : sel;
    }

    // ── New bulk parts handler
    // ────────────────────────────────────────────────────
    @FXML
    public void handleGenerateAllParts() {
        if (currentPage == null || currentEntries.isEmpty())
            return;

        // Ask for confirmation since it overwrites existing parts
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Generate All Arabic Parts");
        confirm.setHeaderText("Generate parts for all " + currentEntries.size() + " words?");
        confirm.setContentText("This will overwrite existing parts for this page sequentially.");
        ButtonType btnAll = new ButtonType("Generate All", ButtonBar.ButtonData.YES);
        ButtonType btnSel = new ButtonType("Selected Only", ButtonBar.ButtonData.NO);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirm.getButtonTypes().setAll(btnAll, btnSel, btnCancel);

        confirm.showAndWait().ifPresent(btn -> {
            if (btn == btnCancel)
                return;
            List<TertiaryWordEntry> targets = btn == btnSel ? getTargets()
                    : new ArrayList<>(currentEntries);
            if (!targets.isEmpty()) {
                AppLogger.info("Starting Arabic parts generation for "
                        + targets.size() + " words on page " + currentPage.getPageNumber());
                runSequential(targets, 0, TaskType.ARABIC_PARTS);
            }
        });
    }

    // ── Open log/stats window
    // ─────────────────────────────────────────────────────
    @FXML
    public void handleOpenLogs() {
        LogAndStatsPane.openWindow();
    }

    // =========================================================================
    // Sequential task runner
    // =========================================================================
    private void runSequential(List<TertiaryWordEntry> entries, int index, TaskType type) {
        if (index >= entries.size() || currentPage == null)
            return;
        TertiaryWordEntry entry = entries.get(index);

        switch (type) {
            case IMAGE -> entry.setImageProcessing(true);
            case AUDIO -> entry.setAudioProcessing(true);
            case ARABIC_PARTS -> entry.setPartsProcessing(true);
        }
        Platform.runLater(() -> wordsTable.refresh());

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return switch (type) {
                    case IMAGE -> generateImage(entry.getArabic(), entry.getBangla());
                    case AUDIO -> generateAudio(entry.getArabic());
                    case ARABIC_PARTS -> generateArabicParts(entry.getArabic());
                };
            }
        };

        task.setOnSucceeded(e -> {
            String result = task.getValue();
            switch (type) {
                case IMAGE -> {
                    deleteOldFile(entry.getImagePath());
                    entry.setImagePath(result);
                    entry.setImageProcessing(false);
                }
                case AUDIO -> {
                    deleteOldFile(entry.getAudioPath());
                    entry.setAudioPath(result);
                    entry.setAudioProcessing(false);
                }
                case ARABIC_PARTS -> {
                    try {
                        List<String> freshParts = mapper.readValue(result,
                                new TypeReference<List<String>>() {
                                });
                        entry.getArabicParts().setAll(freshParts); // ← setAll not add
                    } catch (Exception ex) {
                        System.err.println("Parts parse failed: " + ex.getMessage());
                        entry.getArabicParts().setAll(List.of(result)); // fallback
                    }
                    entry.setPartsProcessing(false);
                }
            }
            markDirty();
            syncEntryToMap(currentPage, entry.getIndex(), entry);
            saveJsonToFile();
            Platform.runLater(() -> wordsTable.refresh());
            runSequential(entries, index + 1, type);
        });

        task.setOnFailed(e -> {
            switch (type) {
                case IMAGE -> entry.setImageProcessing(false);
                case AUDIO -> entry.setAudioProcessing(false);
                case ARABIC_PARTS -> entry.setPartsProcessing(false);
            }
            System.err.println("Task failed: " + task.getException().getMessage());
            Platform.runLater(() -> wordsTable.refresh());
            runSequential(entries, index + 1, type);
        });

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }
    // =========================================================================
    // File deletion helper (with confirmation)
    // =========================================================================

    private void deleteOldFile(String path) {
        if (path == null || path.isEmpty())
            return;
        File f = new File(path);
        if (!f.exists())
            return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Old File");
        confirm.setHeaderText("Replace existing file?");
        confirm.setContentText("Old file will be deleted:\n" + f.getName());
        ButtonType yes = new ButtonType("Delete & Replace", ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType("Keep Old", ButtonBar.ButtonData.NO);
        confirm.getButtonTypes().setAll(yes, no);

        confirm.showAndWait().ifPresent(btn -> {
            if (btn == yes)
                f.delete();
        });
    }

    // =========================================================================
    // ★ STUBS — replace with real implementations ★
    // =========================================================================

    private String generateImage(String arabic, String bangla) throws Exception {
        Thread.sleep(500);
        return ""; // return absolute path to saved image
    }

    private String generateAudio(String arabicText) throws Exception {
        File audioDir = new File("output_pages/audio");

        if (audioProvider == ApiKeyManager.AudioProvider.LAHAJATI) {
            AppLogger.info("Using Lahajati for: " + arabicText);
            return lahajatiService.generateAudio(arabicText, audioDir);
        } else {
            AppLogger.info("Using Gemini TTS for: " + arabicText);
            return getGeminiService().generateArabicAudio(arabicText, audioDir);
        }
    }

    private String generateArabicParts(String arabic) throws Exception {
        GeminiService svc = getGeminiService();
        if (svc == null)
            throw new RuntimeException("No Gemini API key.");
        List<String> parts = svc.extractArabicParts(arabic);
        return mapper.writeValueAsString(parts); // stored as JSON array string
    }

    // Add this helper to TertiaryController if not already there
    private GeminiService getGeminiService() {
        String key = ApiKeyManager.load();
        if (key.isEmpty())
            return null;
        GeminiService svc = new GeminiService(key);
        svc.setTextModel(ApiKeyManager.loadTextModel());
        return svc;
    }

    // =========================================================================
    // Page list cell — no dismiss, only check badge
    // =========================================================================

    private class PageCell extends ListCell<PageData> {
        private final Label pageLabel = new Label();
        private final Label checkedBadge = new Label("✅");
        private final HBox row = new HBox(8, pageLabel, checkedBadge);

        PageCell() {
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(pageLabel, Priority.ALWAYS);
            checkedBadge.setStyle("-fx-text-fill: green;");
            setStyle("-fx-padding: 6 8 6 8;");
        }

        @Override
        protected void updateItem(PageData item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            pageLabel.setText("📄 Page " + item.getPageNumber());
            boolean checked = item.isTertiaryFullyChecked();
            checkedBadge.setVisible(checked);
            checkedBadge.setManaged(checked);
            setGraphic(row);
        }
    }

    // =========================================================================
    // Image cell
    // =========================================================================

    private class ImageCell extends TableCell<TertiaryWordEntry, TertiaryWordEntry> {
        private final ImageView thumb = new ImageView();
        private final Button genBtn = new Button("Generate");
        private final Button regenBtn = new Button("Regenerate");
        private final Label spinner = new Label("⏳");
        private final VBox box = new VBox(4);

        ImageCell() {
            thumb.setFitWidth(70);
            thumb.setFitHeight(70);
            thumb.setPreserveRatio(true);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(4));
            box.setStyle("-fx-border-color:#ddd; -fx-border-radius:4;");
            for (Button b : List.of(genBtn, regenBtn))
                b.setStyle("-fx-font-size: 10;");

            genBtn.setOnAction(e -> {
                if (getItem() != null)
                    runSequential(List.of(getItem()), 0, TaskType.IMAGE);
            });
            regenBtn.setOnAction(e -> {
                if (getItem() == null)
                    return;
                deleteOldFile(getItem().getImagePath());
                runSequential(List.of(getItem()), 0, TaskType.IMAGE);
            });

            setupDrop(box, "images", (entry, dest) -> {
                deleteOldFile(entry.getImagePath());
                entry.setImagePath(dest);
            });
        }

        @Override
        protected void updateItem(TertiaryWordEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            box.getChildren().clear();
            boolean hasImg = !item.getImagePath().isEmpty() && new File(item.getImagePath()).exists();
            if (hasImg) {
                thumb.setImage(new Image(new File(item.getImagePath()).toURI().toString()));
                box.getChildren().addAll(thumb, regenBtn);
            } else {
                box.getChildren().add(genBtn);
            }
            if (item.isImageProcessing())
                box.getChildren().add(spinner);
            setGraphic(box);
        }
    }

    // =========================================================================
    // Audio cell
    // =========================================================================

    private class AudioCell extends TableCell<TertiaryWordEntry, TertiaryWordEntry> {
        private final Button playBtn = new Button("▶ Play");
        private final Button stopBtn = new Button("⏹");
        private final Button genBtn = new Button("Generate");
        private final Button regenBtn = new Button("Regenerate");
        private final Label spinner = new Label("⏳");
        private final VBox box = new VBox(4);
        private MediaPlayer player = null;

        AudioCell() {
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(4));
            box.setStyle("-fx-border-color:#ddd; -fx-border-radius:4;");
            for (Button b : List.of(playBtn, stopBtn, genBtn, regenBtn))
                b.setStyle("-fx-font-size: 10;");

            playBtn.setOnAction(e -> {
                TertiaryWordEntry item = getItem();
                if (item == null || item.getAudioPath().isEmpty())
                    return;
                File f = new File(item.getAudioPath());
                if (!f.exists())
                    return;
                if (player != null) {
                    player.stop();
                    player.dispose();
                }
                player = new MediaPlayer(new Media(f.toURI().toString()));
                player.play();
            });
            stopBtn.setOnAction(e -> {
                if (player != null) {
                    player.stop();
                    player.dispose();
                    player = null;
                }
            });
            genBtn.setOnAction(e -> {
                if (getItem() != null)
                    runSequential(List.of(getItem()), 0, TaskType.AUDIO);
            });
            regenBtn.setOnAction(e -> {
                if (getItem() == null)
                    return;
                deleteOldFile(getItem().getAudioPath());
                runSequential(List.of(getItem()), 0, TaskType.AUDIO);
            });

            setupDrop(box, "audio", (entry, dest) -> {
                deleteOldFile(entry.getAudioPath());
                entry.setAudioPath(dest);
            });
        }

        @Override
        protected void updateItem(TertiaryWordEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            box.getChildren().clear();
            boolean hasAudio = !item.getAudioPath().isEmpty() && new File(item.getAudioPath()).exists();
            if (hasAudio) {
                HBox playRow = new HBox(4, playBtn, stopBtn);
                playRow.setAlignment(Pos.CENTER);
                box.getChildren().addAll(playRow, regenBtn);
            } else {
                box.getChildren().add(genBtn);
            }
            if (item.isAudioProcessing())
                box.getChildren().add(spinner);
            setGraphic(box);
        }
    }

    // =========================================================================
    // Arabic Parts cell — each part is a separate editable TextField row
    // =========================================================================

    private class ArabicPartsCell extends TableCell<TertiaryWordEntry, TertiaryWordEntry> {
        private final VBox box = new VBox(3);
        private final Button addBtn = new Button("+ Add Part");
        private final Button genBtn = new Button("⚡ Generate Part");
        private final Label spin = new Label("⏳");

        ArabicPartsCell() {
            box.setPadding(new Insets(4));
            addBtn.setStyle("-fx-font-size: 10;");
            genBtn.setStyle("-fx-font-size: 10;");

            genBtn.setOnAction(e -> {
                if (getItem() != null)
                    runSequential(List.of(getItem()), 0, TaskType.ARABIC_PARTS);
            });
            addBtn.setOnAction(e -> {
                TertiaryWordEntry item = getItem();
                if (item == null)
                    return;
                item.getArabicParts().add("");
                updateItem(item, false);
            });
        }

        @Override
        protected void updateItem(TertiaryWordEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            box.getChildren().clear();

            // One row per part
            for (int i = 0; i < item.getArabicParts().size(); i++) {
                final int idx = i;
                TextField tf = new TextField(item.getArabicParts().get(i));
                tf.setStyle("-fx-font-size: 12; -fx-pref-width: 130;");
                tf.setPromptText("Part " + (i + 1));

                Button delBtn = new Button("✕");
                delBtn.setStyle("-fx-font-size: 9; -fx-padding: 2 5 2 5;");
                delBtn.setOnAction(e -> {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("Delete Part");
                    confirm.setHeaderText(null);
                    confirm.setContentText("Delete part \"" + item.getArabicParts().get(idx) + "\"?");
                    ButtonType yes = new ButtonType("Delete", ButtonBar.ButtonData.YES);
                    ButtonType no = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                    confirm.getButtonTypes().setAll(yes, no);
                    confirm.showAndWait().ifPresent(btn -> {
                        if (btn == yes) {
                            item.getArabicParts().remove(idx);
                            if (currentPage != null) {
                                syncEntryToMap(currentPage, item.getIndex(), item);
                                saveJsonToFile();
                            }
                            updateItem(item, false);
                        }
                    });
                });

                // Save part text on focus lost
                tf.focusedProperty().addListener((obs, o, focused) -> {
                    if (!focused && idx < item.getArabicParts().size()) {
                        item.getArabicParts().set(idx, tf.getText());
                        markDirty();
                        if (currentPage != null) {
                            syncEntryToMap(currentPage, item.getIndex(), item);
                            saveJsonToFile();
                        }
                    }
                });

                HBox row = new HBox(3, tf, delBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().add(row);
            }

            HBox bottomRow = new HBox(4, addBtn, genBtn);
            bottomRow.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(bottomRow);

            if (item.isPartsProcessing())
                box.getChildren().add(spin);
            setGraphic(box);
        }
    }

    // =========================================================================
    // Drag-drop helper
    // =========================================================================

    @FunctionalInterface
    interface DropHandler {
        void handle(TertiaryWordEntry entry, String destPath);
    }

    private void setupDrop(VBox box, String subDir, DropHandler onDrop) {
        box.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles())
                e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        box.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().getFiles();
            TableCell<TertiaryWordEntry, TertiaryWordEntry> cell = (TableCell<TertiaryWordEntry, TertiaryWordEntry>) box
                    .getParent();
            if (!files.isEmpty() && currentPage != null) {
                // Find the entry via the box's table row
                TertiaryWordEntry entry = null;
                for (TertiaryWordEntry en : currentEntries) {
                    if (box.getUserData() != null && box.getUserData().equals(en)) {
                        entry = en;
                        break;
                    }
                }
                // fallback: use selected
                if (entry == null && !wordsTable.getSelectionModel().isEmpty())
                    entry = wordsTable.getSelectionModel().getSelectedItem();
                if (entry != null) {
                    try {
                        File dest = copyMedia(files.get(0), subDir);
                        onDrop.handle(entry, dest.getAbsolutePath());
                        markDirty();
                        syncEntryToMap(currentPage, entry.getIndex(), entry);
                        saveJsonToFile();
                        wordsTable.refresh();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private File copyMedia(File src, String subDir) throws IOException {
        File dir = new File("output_pages/" + subDir);
        dir.mkdirs();
        File dest = new File(dir, System.currentTimeMillis() + "_" + src.getName());
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    private void loadData() {
        File file = new File(JSON_PATH);
        if (!file.exists())
            return;
        try {
            List<PageData> loaded = mapper.readValue(file, new TypeReference<>() {
            });
            allPages.setAll(loaded);

            PageData first = allPages.stream()
                    .filter(p -> !p.isTertiaryFullyChecked())
                    .findFirst()
                    .orElse(allPages.isEmpty() ? null : allPages.get(0));

            if (first != null) {
                pageListView.getSelectionModel().select(first);
                loadPageWords(first);
            }
        } catch (IOException e) {
            System.err.println("Load failed: " + e.getMessage());
        }
    }

    private void saveJsonToFile() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(JSON_PATH), allPages);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // TertiaryWordEntry model
    // =========================================================================

    public static class TertiaryWordEntry {
        private final int index;

        // Add inside TertiaryWordEntry class:
        public void setArabic(String v) {
            arabic.set(v);
        }

        public void setBangla(String v) {
            bangla.set(v);
        }

        private final StringProperty arabic = new SimpleStringProperty();
        private final StringProperty bangla = new SimpleStringProperty();
        private final StringProperty imagePath = new SimpleStringProperty();
        private final StringProperty audioPath = new SimpleStringProperty();
        private final ObservableList<String> arabicParts = FXCollections.observableArrayList();
        private final BooleanProperty tertiaryChecked = new SimpleBooleanProperty();
        private final BooleanProperty selected = new SimpleBooleanProperty();
        private final BooleanProperty imageProcessing = new SimpleBooleanProperty();
        private final BooleanProperty audioProcessing = new SimpleBooleanProperty();
        private final BooleanProperty partsProcessing = new SimpleBooleanProperty();

        TertiaryWordEntry(int index, String arabic, String bangla,
                String imagePath, String audioPath,
                List<String> arabicParts, boolean tertiaryChecked) {
            this.index = index;
            this.arabic.set(arabic);
            this.bangla.set(bangla);
            this.imagePath.set(imagePath != null ? imagePath : "");
            this.audioPath.set(audioPath != null ? audioPath : "");
            if (arabicParts != null)
                this.arabicParts.addAll(arabicParts);
            this.tertiaryChecked.set(tertiaryChecked);
        }

        public int getIndex() {
            return index;
        }

        public String getArabic() {
            return arabic.get();
        }

        public String getBangla() {
            return bangla.get();
        }

        public String getImagePath() {
            return imagePath.get();
        }

        public String getAudioPath() {
            return audioPath.get();
        }

        public ObservableList<String> getArabicParts() {
            return arabicParts;
        }

        public boolean isTertiaryChecked() {
            return tertiaryChecked.get();
        }

        public boolean isSelected() {
            return selected.get();
        }

        public boolean isImageProcessing() {
            return imageProcessing.get();
        }

        public boolean isAudioProcessing() {
            return audioProcessing.get();
        }

        public boolean isPartsProcessing() {
            return partsProcessing.get();
        }

        public void setImageProcessing(boolean v) {
            imageProcessing.set(v);
        }

        public void setAudioProcessing(boolean v) {
            audioProcessing.set(v);
        }

        public void setPartsProcessing(boolean v) {
            partsProcessing.set(v);
        }

        public StringProperty arabicProperty() {
            return arabic;
        }

        public StringProperty banglaProperty() {
            return bangla;
        }

        public BooleanProperty tertiaryCheckedProperty() {
            return tertiaryChecked;
        }

        public BooleanProperty selectedProperty() {
            return selected;
        }

        public void setImagePath(String v) {
            imagePath.set(v);
        }

        public void setAudioPath(String v) {
            audioPath.set(v);
        }

        public void setTertiaryChecked(boolean v) {
            tertiaryChecked.set(v);
        }

        public void setSelected(boolean v) {
            selected.set(v);
        }

    }

    // =========================================================================
    // Verify cell — button that calls Gemini and shows a confirmation dialog
    // =========================================================================
    private class VerifyCell extends TableCell<TertiaryWordEntry, TertiaryWordEntry> {

        private final Button verifyBtn = new Button("🔍 Verify");
        private final Label statusIcon = new Label();
        private final Label spinner = new Label("⏳");
        private final VBox box = new VBox(4, verifyBtn, statusIcon);

        VerifyCell() {
            box.setAlignment(Pos.CENTER);
            verifyBtn.setStyle("-fx-font-size: 10;");
            statusIcon.setStyle("-fx-font-size: 10;");

            verifyBtn.setOnAction(e -> {
                TertiaryWordEntry item = getItem();
                if (item == null || currentPage == null)
                    return;

                GeminiService svc = getGeminiService();
                if (svc == null) {
                    new Alert(Alert.AlertType.ERROR, "Set your Gemini API key first.").showAndWait();
                    return;
                }

                box.getChildren().setAll(spinner);

                Task<GeminiService.VerifyResult> task = new Task<>() {
                    @Override
                    protected GeminiService.VerifyResult call() throws Exception {
                        return svc.verifyWordPair(
                                new File(currentPage.getFilePath()),
                                item.getArabic(),
                                item.getBangla());
                    }
                };

                task.setOnSucceeded(ev -> {
                    GeminiService.VerifyResult result = task.getValue();
                    box.getChildren().setAll(verifyBtn, statusIcon);
                    Platform.runLater(() -> showVerifyDialog(item, result));
                });

                task.setOnFailed(ev -> {
                    box.getChildren().setAll(verifyBtn, statusIcon);
                    String msg = task.getException() != null
                            ? task.getException().getMessage()
                            : "Unknown error";
                    AppLogger.error("Verify failed: " + msg);
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, msg).showAndWait());
                });

                new Thread(task) {
                    {
                        setDaemon(true);
                    }
                }.start();
            });
        }

        private void showVerifyDialog(TertiaryWordEntry item, GeminiService.VerifyResult result) {
            boolean arabicChanged = !result.correctedArabic().equals(item.getArabic());
            boolean banglaChanged = !result.correctedBangla().equals(item.getBangla());
            boolean anyChanged = arabicChanged || banglaChanged;

            // ── Determine match type ──────────────────────────────────────────
            String matchType;
            String matchColor;
            if (result.matches() && !anyChanged) {
                matchType = "✅ Exact Match — word confirmed on page";
                matchColor = "#27ae60";
            } else if (result.matches() && anyChanged) {
                matchType = "🟡 Found on page with corrections";
                matchColor = "#e67e22";
            } else {
                matchType = "❌ Not confirmed on page";
                matchColor = "#e74c3c";
            }

            AppLogger.info("Verify [" + item.getArabic() + "]: " + matchType
                    + " | Note: " + result.note());

            // ── Build dialog ──────────────────────────────────────────────────
            Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
            dialog.setTitle("Verification Result");
            dialog.setHeaderText(null);

            Label matchLabel = new Label(matchType);
            matchLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + matchColor
                    + "; -fx-font-size: 12;");

            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(12);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));

            grid.add(bold("Field"), 0, 0);
            grid.add(bold("Your Data"), 1, 0);
            grid.add(bold("Gemini's Version"), 2, 0);

            grid.add(new Label("Arabic"), 0, 1);
            grid.add(plain(item.getArabic()), 1, 1);
            grid.add(colored(result.correctedArabic(), arabicChanged ? matchColor : null), 2, 1);

            grid.add(new Label("Bangla"), 0, 2);
            grid.add(plain(item.getBangla()), 1, 2);
            grid.add(colored(result.correctedBangla(), banglaChanged ? matchColor : null), 2, 2);

            if (!result.note().isBlank()) {
                grid.add(bold("Note"), 0, 3);
                Label noteLabel = new Label(result.note());
                noteLabel.setWrapText(true);
                noteLabel.setMaxWidth(340);
                grid.add(noteLabel, 1, 3, 2, 1);
            }

            VBox content = new VBox(8, matchLabel, grid);
            content.setPadding(new Insets(4));
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().setPrefWidth(520);

            // ── Buttons ───────────────────────────────────────────────────────
            ButtonType btnAccept = new ButtonType("✅ Accept & Mark Checked", ButtonBar.ButtonData.YES);
            ButtonType btnKeep = new ButtonType("Keep My Version", ButtonBar.ButtonData.NO);
            ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            if (anyChanged) {
                dialog.getButtonTypes().setAll(btnAccept, btnKeep, btnCancel);
            } else if (result.matches()) {
                // Exact match — just offer to mark checked
                ButtonType btnCheck = new ButtonType("✅ Mark as Checked", ButtonBar.ButtonData.YES);
                dialog.getButtonTypes().setAll(btnCheck, ButtonType.CANCEL);
            } else {
                dialog.getButtonTypes().setAll(ButtonType.OK);
            }

            dialog.showAndWait().ifPresent(btn -> {
                if (btn.getButtonData() == ButtonBar.ButtonData.YES) {
                    // Apply corrected text if changed
                    if (arabicChanged)
                        item.setArabic(result.correctedArabic());
                    if (banglaChanged)
                        item.setBangla(result.correctedBangla());

                    // Auto-mark tertiaryChecked = true on confirmation
                    item.setTertiaryChecked(true);

                    syncEntryToMap(currentPage, item.getIndex(), item);
                    saveJsonToFile();
                    markDirty();
                    wordsTable.refresh();
                    pageListView.refresh();

                    // Update status icon in this cell
                    statusIcon.setText("✅");
                    statusIcon.setStyle("-fx-text-fill: " + matchColor + "; -fx-font-size: 11;");

                    AppLogger.success("Verified & checked: " + item.getArabic());
                }
            });
        }

        private Label bold(String t) {
            Label l = new Label(t);
            l.setStyle("-fx-font-weight: bold;");
            return l;
        }

        private Label plain(String t) {
            return new Label(t);
        }

        private Label colored(String t, String color) {
            Label l = new Label(t);
            if (color != null)
                l.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
            return l;
        }

        @Override
        protected void updateItem(TertiaryWordEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            // Restore status icon if already checked
            if (item.isTertiaryChecked()) {
                statusIcon.setText("✅");
                statusIcon.setStyle("-fx-text-fill: #27ae60; -fx-font-size: 11;");
            } else {
                statusIcon.setText("");
            }
            box.getChildren().setAll(verifyBtn, statusIcon);
            setGraphic(box);
        }
    }

    // =========================================================================
    // Parts check cell — auto concat check, no user interaction needed
    // =========================================================================
    private class PartsCheckCell extends TableCell<TertiaryWordEntry, TertiaryWordEntry> {
        private final Label icon = new Label();

        PartsCheckCell() {
            icon.setStyle("-fx-font-size: 16;");
            setAlignment(Pos.CENTER);
        }

        @Override
        protected void updateItem(TertiaryWordEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            List<String> parts = item.getArabicParts();
            if (parts == null || parts.isEmpty()) {
                icon.setText("—");
                icon.setStyle("-fx-font-size: 13; -fx-text-fill: #aaa;");
            } else {
                String joined = String.join("", parts);
                if (joined.equals(item.getArabic())) {
                    icon.setText("✅");
                    icon.setStyle("-fx-font-size: 16;");
                    icon.setTooltip(new Tooltip("Parts correctly reconstruct the word:\n" + joined));
                } else {
                    icon.setText("❌");
                    icon.setStyle("-fx-font-size: 16;");
                    icon.setTooltip(new Tooltip(
                            "Mismatch!\nExpected : " + item.getArabic()
                                    + "\nGot      : " + joined));
                }
            }
            setGraphic(icon);
        }
    }
}