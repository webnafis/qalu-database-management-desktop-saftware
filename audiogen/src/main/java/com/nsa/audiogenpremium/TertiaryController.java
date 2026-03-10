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

    private PageData currentPage = null;

    private enum TaskType {
        IMAGE, AUDIO, ARABIC_PARTS
    }

    // =========================================================================
    // Init
    // =========================================================================

    @FXML
    private void switchToSecondary() throws IOException {
        App.setRoot("secondary");
    }

    @FXML
    public void initialize() {
        setupPageList();
        setupWordsTable();
        loadData();
    }

    // =========================================================================
    // Page list — no dismiss, just check badge
    // =========================================================================

    private void setupPageList() {
        pageListView.setCellFactory(lv -> new PageCell());
        pageListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> {
                    if (n != null)
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

        wordsTable.getColumns().addAll(selCol, arabicCol, banglaCol, imageCol, audioCol, partsCol, checkedCol);
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
        int idx = allPages.indexOf(currentPage);
        if (idx > 0)
            pageListView.getSelectionModel().select(allPages.get(idx - 1));
    }

    @FXML
    public void handleNext() {
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
        flashSaveButton();
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

    // =========================================================================
    // Sequential task runner
    // =========================================================================

    private void runSequential(List<TertiaryWordEntry> entries, int index, TaskType type) {
        if (index >= entries.size() || currentPage == null)
            return;
        TertiaryWordEntry entry = entries.get(index);

        entry.setProcessing(true);
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
                }
                case AUDIO -> {
                    deleteOldFile(entry.getAudioPath());
                    entry.setAudioPath(result);
                }
                case ARABIC_PARTS -> {
                    // single generated part — add to list
                    entry.getArabicParts().add(result);
                }
            }
            entry.setProcessing(false);
            syncEntryToMap(currentPage, entry.getIndex(), entry);
            saveJsonToFile();
            Platform.runLater(() -> wordsTable.refresh());
            runSequential(entries, index + 1, type);
        });

        task.setOnFailed(e -> {
            entry.setProcessing(false);
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

    private String generateAudio(String arabic) throws Exception {
        Thread.sleep(500);
        return ""; // return absolute path to saved audio
    }

    private String generateArabicParts(String arabic) throws Exception {
        Thread.sleep(300);
        return arabic; // return one part string
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
            if (item.isProcessing())
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
            if (item.isProcessing())
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

            if (item.isProcessing())
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
        private final StringProperty arabic = new SimpleStringProperty();
        private final StringProperty bangla = new SimpleStringProperty();
        private final StringProperty imagePath = new SimpleStringProperty();
        private final StringProperty audioPath = new SimpleStringProperty();
        private final ObservableList<String> arabicParts = FXCollections.observableArrayList();
        private final BooleanProperty tertiaryChecked = new SimpleBooleanProperty();
        private final BooleanProperty selected = new SimpleBooleanProperty();
        private final BooleanProperty processing = new SimpleBooleanProperty();

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

        public boolean isProcessing() {
            return processing.get();
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

        public void setProcessing(boolean v) {
            processing.set(v);
        }
    }
}