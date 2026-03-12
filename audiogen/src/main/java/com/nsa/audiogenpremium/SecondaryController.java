package com.nsa.audiogenpremium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SecondaryController {
    @FXML
    private void switchToTertiary() throws IOException {
        App.setRoot("tertiary");
    }

    private GeminiService geminiService = null;
    @FXML
    private TabPane pdfTabPane;
    @FXML
    private ListView<PageData> jsonListView;
    @FXML
    private Button switchToPrimaryBtn; // ← needs fx:id in FXML

    private static final String JSON_PATH = "output_data.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObservableList<PageData> masterList = FXCollections.observableArrayList();

    private final Set<Integer> selectedPages = new HashSet<>();
    private final Map<Integer, FetchStatus> fetchStatusMap = new ConcurrentHashMap<>();

    // Track open words windows so we never open two for the same page
    private final Map<Integer, Stage> openWordsWindows = new HashMap<>();

    private enum FetchStatus {
        IDLE, LOADING, DONE, ERROR
    }

    // =========================================================================
    // Init
    // =========================================================================

    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }

    @FXML
    public void initialize() {
        jsonListView.setCellFactory(lv -> new PageCell());
        jsonListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> {
                    if (n != null)
                        openPdfInTab(n);
                });
        loadDataFromFile();
        jsonListView.setItems(masterList);
    }

    // =========================================================================
    // Toolbar actions
    // =========================================================================

    @FXML
    public void handleSelectAll() {
        boolean allSelected = masterList.stream()
                .allMatch(p -> selectedPages.contains(p.getPageNumber()));
        if (allSelected)
            selectedPages.clear();
        else
            masterList.forEach(p -> selectedPages.add(p.getPageNumber()));
        jsonListView.refresh();
    }

    @FXML
    public void handleFetchSelected() {
        List<PageData> toFetch = masterList.stream()
                .filter(p -> selectedPages.contains(p.getPageNumber())).toList();
        if (!toFetch.isEmpty())
            fetchSequentially(toFetch, 0);
    }

    @FXML
    public void handleFetchAll() {
        fetchSequentially(new ArrayList<>(masterList), 0);
    }

    @FXML
    public void handleForceFetchSelected() {
        List<PageData> toFetch = masterList.stream()
                .filter(p -> selectedPages.contains(p.getPageNumber())).toList();
        if (!toFetch.isEmpty())
            fetchSequentiallyForced(toFetch, 0);
    }

    @FXML
    public void handleForceFetchAll() {
        fetchSequentiallyForced(new ArrayList<>(masterList), 0);
    }

    // Same as fetchSequentially but skips the confirmation dialog entirely
    private void fetchSequentiallyForced(List<PageData> pages, int index) {
        if (index >= pages.size())
            return;
        PageData page = pages.get(index);

        fetchStatusMap.put(page.getPageNumber(), FetchStatus.LOADING);
        Platform.runLater(() -> jsonListView.refresh());

        Task<FetchResult> task = new Task<>() {
            @Override
            protected FetchResult call() throws Exception {
                return fetchWordsFromPdf(new File(page.getFilePath()));
            }
        };
        task.setOnSucceeded(e -> {
            FetchResult result = task.getValue();

            // Still preserve checked flags even on force re-fetch
            Map<Integer, String> checkedFlags = new HashMap<>();
            if (page.getWordsinfo() != null)
                for (int i = 0; i < page.getWordsinfo().size(); i++)
                    checkedFlags.put(i, page.getWordsinfo().get(i).getOrDefault("checked", "false"));

            for (int i = 0; i < result.words().size(); i++)
                result.words().get(i).put("checked", checkedFlags.getOrDefault(i, "false"));

            page.setWordsinfo(result.words());
            page.setTotalWords(result.totalWords());
            fetchStatusMap.put(page.getPageNumber(), FetchStatus.DONE);
            saveJsonToFile();
            Platform.runLater(() -> jsonListView.refresh());
            fetchSequentiallyForced(pages, index + 1);
        });
        task.setOnFailed(e -> {
            fetchStatusMap.put(page.getPageNumber(), FetchStatus.ERROR);
            System.err.println("Force fetch failed p" + page.getPageNumber()
                    + ": " + task.getException().getMessage());
            Platform.runLater(() -> jsonListView.refresh());
            fetchSequentiallyForced(pages, index + 1);
        });
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void handleUpload() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        File selected = fc.showOpenDialog(null);
        if (selected != null)
            processPDF(selected);
    }

    // =========================================================================
    // Sequential fetch
    // =========================================================================
    private void fetchSequentially(List<PageData> pages, int index) {
        if (index >= pages.size())
            return;
        PageData page = pages.get(index);

        // ── Confirm before overwriting existing data ──────────────────────────────
        boolean hasExisting = page.getWordsinfo() != null && !page.getWordsinfo().isEmpty();
        if (hasExisting) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Re-fetch Confirmation");
            confirm.setHeaderText("Page " + page.getPageNumber() + " already has word data.");
            confirm.setContentText("Fetching again will overwrite the existing "
                    + page.getWordsinfo().size() + " word(s).\n\nContinue?");

            // Custom buttons so it's crystal clear
            ButtonType btnYes = new ButtonType("Yes, Re-fetch", ButtonBar.ButtonData.YES);
            ButtonType btnSkip = new ButtonType("Skip This Page", ButtonBar.ButtonData.NO);
            ButtonType btnCancelAll = new ButtonType("Cancel All", ButtonBar.ButtonData.CANCEL_CLOSE);
            confirm.getButtonTypes().setAll(btnYes, btnSkip, btnCancelAll);

            Optional<ButtonType> result = confirm.showAndWait();

            if (result.isEmpty() || result.get() == btnCancelAll) {
                return; // stop the whole queue
            }
            if (result.get() == btnSkip) {
                fetchSequentially(pages, index + 1); // skip, move to next
                return;
            }
            // btnYes → fall through to fetch
        }

        // ── Proceed with fetch ────────────────────────────────────────────────────
        fetchStatusMap.put(page.getPageNumber(), FetchStatus.LOADING);
        Platform.runLater(() -> jsonListView.refresh());

        Task<FetchResult> task = new Task<>() {
            @Override
            protected FetchResult call() throws Exception {
                return fetchWordsFromPdf(new File(page.getFilePath()));
            }
        };
        task.setOnSucceeded(e -> {
            FetchResult result = task.getValue();

            // Preserve existing checked flags by index
            Map<Integer, String> checkedFlags = new HashMap<>();
            if (page.getWordsinfo() != null)
                for (int i = 0; i < page.getWordsinfo().size(); i++)
                    checkedFlags.put(i, page.getWordsinfo().get(i).getOrDefault("checked", "false"));

            for (int i = 0; i < result.words().size(); i++)
                result.words().get(i).put("checked", checkedFlags.getOrDefault(i, "false"));

            page.setWordsinfo(result.words());
            page.setTotalWords(result.totalWords());
            fetchStatusMap.put(page.getPageNumber(), FetchStatus.DONE);
            saveJsonToFile();
            Platform.runLater(() -> jsonListView.refresh());
            fetchSequentially(pages, index + 1);
        });
        task.setOnFailed(e -> {
            fetchStatusMap.put(page.getPageNumber(), FetchStatus.ERROR);
            System.err.println("Fetch failed p" + page.getPageNumber()
                    + ": " + task.getException().getMessage());
            Platform.runLater(() -> jsonListView.refresh());
            fetchSequentially(pages, index + 1);
        });
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    // ── Fetch result wrapper ──────────────────────────────────────────────────
    private record FetchResult(List<Map<String, String>> words, int totalWords) {
    }

    // =========================================================================
    // ★ REPLACE THIS with your real implementation later ★ Done!
    // =========================================================================
    private FetchResult fetchWordsFromPdf(File pdfFile) throws Exception {
        GeminiService svc = getOrInitGeminiService();
        if (svc == null)
            throw new RuntimeException("No Gemini API key provided.");
        GeminiService.WordsResult result = svc.extractWordsFromPdf(pdfFile);
        return new FetchResult(new ArrayList<>(result.words()), result.totalWords());
    }

    // ── Lazy init with key prompt
    // ─────────────────────────────────────────────────
    private GeminiService getOrInitGeminiService() {
        if (geminiService != null)
            return geminiService;

        String key = ApiKeyManager.load();

        if (key.isEmpty()) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Gemini API Key Required");
            dialog.setHeaderText("Enter your Gemini API key to enable word extraction.");
            dialog.setContentText("API Key:");
            // Style the dialog
            dialog.getDialogPane().setPrefWidth(460);
            Optional<String> result = dialog.showAndWait();
            key = result.orElse("").trim();
            if (key.isEmpty())
                return null;
            ApiKeyManager.save(key);
        }

        geminiService = new GeminiService(key);
        return geminiService;
    }

    // ── Add a menu/button to change the key ──────────────────────────────────────
    @FXML
    public void handleChangeApiKey() {
        String current = ApiKeyManager.load();
        TextInputDialog dialog = new TextInputDialog(current);
        dialog.setTitle("Change Gemini API Key");
        dialog.setHeaderText("Update your Gemini API key.");
        dialog.setContentText("API Key:");
        dialog.getDialogPane().setPrefWidth(460);
        dialog.showAndWait().ifPresent(key -> {
            if (!key.isBlank()) {
                ApiKeyManager.save(key.trim());
                geminiService = new GeminiService(key.trim()); // reinit with new key
            }
        });
    }

    // private static Map<String, String> mapOf(String arabic, String bangla) {
    // Map<String, String> m = new LinkedHashMap<>();
    // m.put("arabic", arabic);
    // m.put("bangla", bangla);
    // m.put("checked", "false");
    // return m;
    // }

    // =========================================================================
    // Custom ListCell
    // =========================================================================

    private class PageCell extends ListCell<PageData> {
        private final CheckBox checkBox = new CheckBox();
        private final Label pageLabel = new Label();
        private final Label statusIcon = new Label();
        private final Label checkedBadge = new Label("☑ All Checked");
        private final Label wordCountLabel = new Label();
        private final Button wordsBtn = new Button("▼ Words");

        private final HBox row;

        PageCell() {
            checkedBadge.setStyle("-fx-text-fill: #2a7a2a; -fx-font-weight: bold; -fx-font-size: 10;");
            wordCountLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 10;");

            VBox labelBox = new VBox(2, pageLabel, wordCountLabel);
            row = new HBox(8, checkBox, labelBox, statusIcon, checkedBadge, wordsBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(labelBox, Priority.ALWAYS);
            setStyle("-fx-padding: 6 8 6 8;");

            checkBox.setOnAction(e -> {
                PageData item = getItem();
                if (item == null)
                    return;
                if (checkBox.isSelected())
                    selectedPages.add(item.getPageNumber());
                else
                    selectedPages.remove(item.getPageNumber());
            });

            wordsBtn.setOnAction(e -> {
                PageData item = getItem();
                if (item != null)
                    openWordsWindow(item);
            });
        }

        @Override
        protected void updateItem(PageData item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            checkBox.setSelected(selectedPages.contains(item.getPageNumber()));
            pageLabel.setText("📄 Page " + item.getPageNumber());

            // Word count label
            if (item.getTotalWords() > 0)
                wordCountLabel.setText(item.getTotalWords() + " words");
            else
                wordCountLabel.setText("");

            // Status icon
            FetchStatus st = fetchStatusMap.getOrDefault(item.getPageNumber(), FetchStatus.IDLE);
            switch (st) {
                case LOADING -> {
                    statusIcon.setText("⏳");
                    statusIcon.setStyle("-fx-text-fill: orange;");
                }
                case DONE -> {
                    statusIcon.setText("✅");
                    statusIcon.setStyle("");
                }
                case ERROR -> {
                    statusIcon.setText("❌");
                    statusIcon.setStyle("-fx-text-fill: red;");
                }
                default -> {
                    statusIcon.setText("");
                    statusIcon.setStyle("");
                }
            }

            // All-checked badge
            boolean fullyChecked = item.isFullyChecked();
            checkedBadge.setVisible(fullyChecked);
            checkedBadge.setManaged(fullyChecked);

            // Words button
            boolean hasWords = item.getWordsinfo() != null && !item.getWordsinfo().isEmpty();
            wordsBtn.setDisable(!hasWords);

            setGraphic(row);
        }
    }

    // =========================================================================
    // Words window — modeless Stage, disables switchToPrimary + window close
    // =========================================================================

    private void openWordsWindow(PageData page) {
        if (openWordsWindows.containsKey(page.getPageNumber())) {
            openWordsWindows.get(page.getPageNumber()).toFront();
            return;
        }

        ObservableList<WordEntry> entries = FXCollections.observableArrayList();
        if (page.getWordsinfo() != null)
            page.getWordsinfo().forEach(m -> entries.add(new WordEntry(
                    m.getOrDefault("arabic", ""),
                    m.getOrDefault("bangla", ""),
                    "true".equalsIgnoreCase(m.getOrDefault("checked", "false")))));

        // ── Font size state ───────────────────────────────────────────────────────
        final double[] fontSize = { 14.0 };

        // ── Table ─────────────────────────────────────────────────────────────────
        TableView<WordEntry> table = new TableView<>(entries);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-font-size: " + fontSize[0] + "px;");

        // Recalculate row height when font changes
        Runnable applyFont = () -> {
            table.setStyle("-fx-font-size: " + fontSize[0] + "px;");
            table.setFixedCellSize(fontSize[0] * 2.6);
            table.refresh();
        };

        TableColumn<WordEntry, String> arabicCol = new TableColumn<>("Arabic");
        arabicCol.setCellValueFactory(d -> d.getValue().arabicProperty());
        arabicCol.setCellFactory(TextFieldTableCell.forTableColumn());
        arabicCol.setOnEditCommit(e -> e.getRowValue().setArabic(e.getNewValue()));

        TableColumn<WordEntry, String> banglaCol = new TableColumn<>("Bangla");
        banglaCol.setCellValueFactory(d -> d.getValue().banglaProperty());
        banglaCol.setCellFactory(TextFieldTableCell.forTableColumn());
        banglaCol.setOnEditCommit(e -> e.getRowValue().setBangla(e.getNewValue()));

        TableColumn<WordEntry, Boolean> checkedCol = new TableColumn<>("Checked");
        checkedCol.setCellValueFactory(d -> d.getValue().checkedProperty());
        checkedCol.setCellFactory(CheckBoxTableCell.forTableColumn(checkedCol));
        checkedCol.setEditable(true);
        checkedCol.setMaxWidth(80);
        checkedCol.setMinWidth(80);

        entries.forEach(en -> en.checkedProperty().addListener((obs, o, n) -> {
            syncCheckedToPage(page, entries);
            Platform.runLater(() -> jsonListView.refresh());
        }));

        table.getColumns().addAll(arabicCol, banglaCol, checkedCol);

        // ── Zoom controls ─────────────────────────────────────────────────────────
        Button zoomIn = new Button("A+");
        Button zoomOut = new Button("A−");
        Button zoomReset = new Button("A↺");
        Label zoomLabel = new Label(String.format("%.0fpx", fontSize[0]));

        zoomIn.setStyle("-fx-font-weight: bold;");
        zoomOut.setStyle("-fx-font-weight: bold;");

        zoomIn.setOnAction(e -> {
            fontSize[0] = Math.min(32, fontSize[0] + 2);
            zoomLabel.setText(String.format("%.0fpx", fontSize[0]));
            applyFont.run();
        });
        zoomOut.setOnAction(e -> {
            fontSize[0] = Math.max(8, fontSize[0] - 2);
            zoomLabel.setText(String.format("%.0fpx", fontSize[0]));
            applyFont.run();
        });
        zoomReset.setOnAction(e -> {
            fontSize[0] = 14;
            zoomLabel.setText("14px");
            applyFont.run();
        });

        // Also support Ctrl+scroll on the table
        table.setOnScroll(e -> {
            if (e.isControlDown()) {
                fontSize[0] = Math.max(8, Math.min(32, fontSize[0] + (e.getDeltaY() > 0 ? 2 : -2)));
                zoomLabel.setText(String.format("%.0fpx", fontSize[0]));
                applyFont.run();
                e.consume();
            }
        });

        // ── Other toolbar items ───────────────────────────────────────────────────
        Button checkAllBtn = new Button("☑ Check All");
        Button uncheckAllBtn = new Button("☐ Uncheck All");
        Button saveBtn = new Button("💾 Save");
        Label totalLbl = new Label("Total words: " + page.getTotalWords());
        totalLbl.setStyle("-fx-font-weight: bold;");

        checkAllBtn.setOnAction(e -> entries.forEach(en -> en.setChecked(true)));
        uncheckAllBtn.setOnAction(e -> entries.forEach(en -> en.setChecked(false)));
        saveBtn.setOnAction(e -> {
            writeEntriesToPage(page, entries);
            saveJsonToFile();
            jsonListView.refresh();
        });

        Separator sep1 = new Separator(), sep2 = new Separator(), sep3 = new Separator();

        ToolBar tb = new ToolBar(
                zoomOut, zoomLabel, zoomIn, zoomReset, // zoom group
                sep1,
                checkAllBtn, uncheckAllBtn, // check group
                sep2,
                totalLbl, // info
                sep3,
                saveBtn // save
        );

        // Apply initial row height
        applyFont.run();

        VBox root = new VBox(tb, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        // ── Stage ─────────────────────────────────────────────────────────────────
        Stage wordsStage = new Stage();
        wordsStage.setTitle("Words — Page " + page.getPageNumber());
        wordsStage.initModality(Modality.NONE);
        wordsStage.setScene(new Scene(root, 560, 520));

        openWordsWindows.put(page.getPageNumber(), wordsStage);
        lockMainWindow();

        wordsStage.setOnCloseRequest(e -> {
            writeEntriesToPage(page, entries);
            saveJsonToFile();
            jsonListView.refresh();
            openWordsWindows.remove(page.getPageNumber());
            if (openWordsWindows.isEmpty())
                unlockMainWindow();
        });

        wordsStage.show();
    }

    // ── Write WordEntry list back into page's wordsinfo ───────────────────────
    private void writeEntriesToPage(PageData page, ObservableList<WordEntry> entries) {
        List<Map<String, String>> updated = new ArrayList<>();
        entries.forEach(en -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("arabic", en.getArabic());
            m.put("bangla", en.getBangla());
            m.put("checked", String.valueOf(en.isChecked()));
            updated.add(m);
        });
        page.setWordsinfo(updated);
    }

    // ── Sync checked state to page whenever a checkbox changes ───────────────
    private void syncCheckedToPage(PageData page, ObservableList<WordEntry> entries) {
        writeEntriesToPage(page, entries);
        saveJsonToFile();
    }

    // ── Lock / unlock the main window's sensitive controls ───────────────────
    private void lockMainWindow() {
        switchToPrimaryBtn.setDisable(true);
        // Block the main window's X button
        Stage mainStage = (Stage) jsonListView.getScene().getWindow();
        mainStage.setOnCloseRequest(WindowEvent::consume);
    }

    private void unlockMainWindow() {
        switchToPrimaryBtn.setDisable(false);
        Stage mainStage = (Stage) jsonListView.getScene().getWindow();
        mainStage.setOnCloseRequest(null); // restore default close behaviour
    }

    // =========================================================================
    // WordEntry model
    // =========================================================================

    public static class WordEntry {
        private final StringProperty arabic = new SimpleStringProperty();
        private final StringProperty bangla = new SimpleStringProperty();
        private final BooleanProperty checked = new SimpleBooleanProperty();

        WordEntry(String arabic, String bangla, boolean checked) {
            this.arabic.set(arabic);
            this.bangla.set(bangla);
            this.checked.set(checked);
        }

        public StringProperty arabicProperty() {
            return arabic;
        }

        public StringProperty banglaProperty() {
            return bangla;
        }

        public BooleanProperty checkedProperty() {
            return checked;
        }

        public String getArabic() {
            return arabic.get();
        }

        public String getBangla() {
            return bangla.get();
        }

        public boolean isChecked() {
            return checked.get();
        }

        public void setArabic(String v) {
            arabic.set(v);
        }

        public void setBangla(String v) {
            bangla.set(v);
        }

        public void setChecked(boolean v) {
            checked.set(v);
        }
    }

    // =========================================================================
    // PDF viewer (unchanged)
    // =========================================================================

    private void openPdfInTab(PageData data) {
        String tabTitle = "Page " + data.getPageNumber();
        for (Tab tab : pdfTabPane.getTabs()) {
            if (tab.getText().equals(tabTitle)) {
                pdfTabPane.getSelectionModel().select(tab);
                return;
            }
        }
        if (pdfTabPane.getTabs().size() >= 10)
            pdfTabPane.getTabs().remove(0);

        Tab tab = new Tab(tabTitle);
        tab.setContent(buildLoadingPane());
        pdfTabPane.getTabs().add(tab);
        pdfTabPane.getSelectionModel().select(tab);

        File pdfFile = new File(data.getFilePath());
        Task<List<Image>> renderTask = new Task<>() {
            @Override
            protected List<Image> call() throws Exception {
                return renderPdfPages(pdfFile, 150f);
            }
        };
        renderTask.setOnSucceeded(e -> tab.setContent(buildPdfViewer(pdfFile, renderTask.getValue())));
        renderTask.setOnFailed(e -> {
            Throwable ex = renderTask.getException();
            tab.setContent(buildErrorPane(ex != null ? ex.getMessage() : "Unknown error"));
        });
        Thread t = new Thread(renderTask);
        t.setDaemon(true);
        t.start();
    }

    private List<Image> renderPdfPages(File file, float dpi) throws IOException {
        List<Image> images = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++)
                images.add(SwingFXUtils.toFXImage(renderer.renderImageWithDPI(i, dpi), null));
        }
        return images;
    }

    private float calcDpi(PDDocument doc, double viewportWidth, double zoom) {
        float pw = doc.getPage(0).getMediaBox().getWidth();
        return (float) ((viewportWidth / (pw / 72f)) * zoom);
    }

    private Pane buildPdfViewer(File pdfFile, List<Image> initialPages) {
        final double[] zoom = { 1.0 };
        final double[] baseWidth = { 0 };

        VBox pageBox = new VBox(10);
        pageBox.setAlignment(Pos.TOP_CENTER);
        pageBox.setPadding(new Insets(10));

        List<ImageView> imageViews = new ArrayList<>();
        for (Image img : initialPages) {
            ImageView iv = new ImageView(img);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            imageViews.add(iv);
            pageBox.getChildren().add(iv);
        }

        ScrollPane scroll = new ScrollPane(pageBox);
        scroll.setFitToWidth(false);
        scroll.setPannable(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        final Thread[] debounce = { null };
        Runnable rerender = () -> {
            if (debounce[0] != null)
                debounce[0].interrupt();
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    float dpi;
                    try (PDDocument doc = Loader.loadPDF(pdfFile)) {
                        dpi = Math.max(72f, Math.min(600f, calcDpi(doc, baseWidth[0], zoom[0])));
                    }
                    List<Image> fresh = renderPdfPages(pdfFile, dpi);
                    Platform.runLater(() -> {
                        for (int i = 0; i < imageViews.size() && i < fresh.size(); i++) {
                            imageViews.get(i).setImage(fresh.get(i));
                            imageViews.get(i).setFitWidth(baseWidth[0] * zoom[0]);
                        }
                    });
                } catch (InterruptedException ignored) {
                } catch (Exception ex) {
                    System.err.println("Rerender: " + ex.getMessage());
                }
            });
            t.setDaemon(true);
            debounce[0] = t;
            t.start();
        };

        scroll.viewportBoundsProperty().addListener((obs, o, nb) -> {
            double w = nb.getWidth() - 20;
            if (w > 0 && baseWidth[0] != w) {
                baseWidth[0] = w;
                imageViews.forEach(iv -> iv.setFitWidth(w * zoom[0]));
                rerender.run();
            }
        });
        scroll.setOnScroll(e -> {
            if (e.isControlDown()) {
                zoom[0] = Math.max(1.0, Math.min(4.0, zoom[0] + (e.getDeltaY() > 0 ? 0.15 : -0.15)));
                imageViews.forEach(iv -> iv.setFitWidth(baseWidth[0] * zoom[0]));
                rerender.run();
                e.consume();
            }
        });

        Button zoomIn = new Button("🔍 +");
        Button zoomReset = new Button("↺ Fit");
        Label pageCount = new Label(initialPages.size() + " page(s)");
        zoomIn.setOnAction(e -> {
            zoom[0] = Math.min(4.0, zoom[0] + 0.25);
            imageViews.forEach(iv -> iv.setFitWidth(baseWidth[0] * zoom[0]));
            rerender.run();
        });
        zoomReset.setOnAction(e -> {
            zoom[0] = 1.0;
            imageViews.forEach(iv -> iv.setFitWidth(baseWidth[0]));
            rerender.run();
        });

        ToolBar toolbar = new ToolBar(zoomIn, zoomReset, new Separator(), pageCount);
        VBox root = new VBox(toolbar, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return root;
    }

    private Pane buildLoadingPane() {
        VBox box = new VBox(12, new ProgressIndicator(), new Label("Rendering PDF…"));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Pane buildErrorPane(String msg) {
        Label lbl = new Label("⚠ Could not render PDF:\n" + msg);
        lbl.setStyle("-fx-text-fill: red;");
        VBox box = new VBox(lbl);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20));
        return box;
    }

    // =========================================================================
    // JSON persistence
    // =========================================================================

    private void loadDataFromFile() {
        File file = new File(JSON_PATH);
        if (!file.exists())
            return;
        try {
            List<PageData> loaded = mapper.readValue(file, new TypeReference<>() {
            });
            masterList.setAll(loaded);
            masterList.forEach(p -> {
                if (p.getWordsinfo() != null && !p.getWordsinfo().isEmpty())
                    fetchStatusMap.put(p.getPageNumber(), FetchStatus.DONE);
            });
        } catch (IOException e) {
            System.err.println("Load failed: " + e.getMessage());
        }
    }

    private void saveJsonToFile() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(JSON_PATH), masterList);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processPDF(File file) {
        File outputDir = new File("output_pages");
        if (!outputDir.exists())
            outputDir.mkdir();
        List<PageData> newPages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                try (PDDocument single = new PDDocument()) {
                    single.importPage(doc.getPage(i));
                    File out = new File(outputDir, "page_" + (i + 1) + "_" + System.currentTimeMillis() + ".pdf");
                    single.save(out);
                    newPages.add(new PageData(i + 1, out.getAbsolutePath()));
                }
            }
            masterList.addAll(newPages);
            saveJsonToFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}