package com.nsa.audiogenpremium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
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
    private TabPane pdfTabPane;
    @FXML
    private ListView<PageData> jsonListView;

    private static final String JSON_PATH = "output_data.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObservableList<PageData> masterList = FXCollections.observableArrayList();

    // ── Transient UI state (not persisted) ───────────────────────────────────
    private final Set<Integer> selectedPages = new HashSet<>();
    private final Map<Integer, FetchStatus> fetchStatusMap = new ConcurrentHashMap<>();

    private enum FetchStatus {
        IDLE, LOADING, DONE, ERROR
    }

    // =========================================================================
    // FXML wiring
    // =========================================================================

    @FXML
    private void switchToPrimary() throws IOException {
        App.setRoot("primary");
    }

    @FXML
    public void initialize() {
        // Restore DONE status for pages that already have wordsinfo
        masterList.forEach(p -> {
            if (p.getWordsinfo() != null && !p.getWordsinfo().isEmpty())
                fetchStatusMap.put(p.getPageNumber(), FetchStatus.DONE);
        });

        jsonListView.setCellFactory(lv -> new PageCell());
        jsonListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> {
                    if (n != null)
                        openPdfInTab(n);
                });

        loadDataFromFile();
        jsonListView.setItems(masterList);
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────

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
                .filter(p -> selectedPages.contains(p.getPageNumber()))
                .toList();
        if (!toFetch.isEmpty())
            fetchSequentially(toFetch, 0);
    }

    @FXML
    public void handleFetchAll() {
        fetchSequentially(new ArrayList<>(masterList), 0);
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
    // Sequential fetch logic
    // =========================================================================

    private void fetchSequentially(List<PageData> pages, int index) {
        if (index >= pages.size())
            return;

        PageData page = pages.get(index);
        fetchStatusMap.put(page.getPageNumber(), FetchStatus.LOADING);
        Platform.runLater(() -> jsonListView.refresh());

        Task<List<Map<String, String>>> task = new Task<>() {
            @Override
            protected List<Map<String, String>> call() throws Exception {
                return fetchWordsFromPdf(new File(page.getFilePath()));
            }
        };

        task.setOnSucceeded(e -> {
            page.setWordsinfo(task.getValue());
            fetchStatusMap.put(page.getPageNumber(), FetchStatus.DONE);
            saveJsonToFile();
            Platform.runLater(() -> jsonListView.refresh());
            fetchSequentially(pages, index + 1); // ← next page
        });

        task.setOnFailed(e -> {
            fetchStatusMap.put(page.getPageNumber(), FetchStatus.ERROR);
            System.err.println("Fetch failed for page " + page.getPageNumber()
                    + ": " + task.getException().getMessage());
            Platform.runLater(() -> jsonListView.refresh());
            fetchSequentially(pages, index + 1); // ← continue anyway
        });

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // ★ REPLACE THIS with your real implementation later ★
    // =========================================================================
    private List<Map<String, String>> fetchWordsFromPdf(File pdfFile) throws Exception {
        // TODO: implement actual fetch using pdfFile
        // Must return a List of maps, each map having "arabic" and "bangla" keys.
        // Example placeholder:
        Thread.sleep(1000); // simulate network/processing delay
        List<Map<String, String>> result = new ArrayList<>();
        result.add(Map.of("arabic", "مرحبا", "bangla", "হ্যালো"));
        result.add(Map.of("arabic", "كتاب", "bangla", "বই"));
        return result;
    }

    // =========================================================================
    // Custom ListCell
    // =========================================================================

    private class PageCell extends ListCell<PageData> {

        private final CheckBox checkBox = new CheckBox();
        private final Label pageLabel = new Label();
        private final Label statusIcon = new Label();
        private final Button wordsBtn = new Button("▼ Words");
        private final HBox row = new HBox(8, checkBox, pageLabel, statusIcon, wordsBtn);

        PageCell() {
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(pageLabel, Priority.ALWAYS);
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
                    showWordsDialog(item);
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

            boolean hasWords = item.getWordsinfo() != null && !item.getWordsinfo().isEmpty();
            wordsBtn.setDisable(!hasWords);
            wordsBtn.setVisible(true);

            setGraphic(row);
        }
    }

    // =========================================================================
    // Words dialog — editable table of arabic / bangla pairs
    // =========================================================================

    private void showWordsDialog(PageData page) {
        // Build observable list of WordEntry from page's wordsinfo
        ObservableList<WordEntry> entries = FXCollections.observableArrayList();
        if (page.getWordsinfo() != null) {
            page.getWordsinfo().forEach(m -> entries.add(
                    new WordEntry(m.getOrDefault("arabic", ""), m.getOrDefault("bangla", ""))));
        }

        // TableView
        TableView<WordEntry> table = new TableView<>(entries);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<WordEntry, String> arabicCol = new TableColumn<>("Arabic");
        arabicCol.setCellValueFactory(d -> d.getValue().arabicProperty());
        arabicCol.setCellFactory(TextFieldTableCell.forTableColumn());
        arabicCol.setOnEditCommit(e -> e.getRowValue().setArabic(e.getNewValue()));

        TableColumn<WordEntry, String> banglaCol = new TableColumn<>("Bangla");
        banglaCol.setCellValueFactory(d -> d.getValue().banglaProperty());
        banglaCol.setCellFactory(TextFieldTableCell.forTableColumn());
        banglaCol.setOnEditCommit(e -> e.getRowValue().setBangla(e.getNewValue()));

        table.getColumns().addAll(arabicCol, banglaCol);
        table.setPrefSize(480, 420);

        // Dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Words — Page " + page.getPageNumber());
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(table);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(500);

        dialog.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.OK)
                return;
            // Write edits back to page
            List<Map<String, String>> updated = new ArrayList<>();
            entries.forEach(en -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("arabic", en.getArabic());
                m.put("bangla", en.getBangla());
                updated.add(m);
            });
            page.setWordsinfo(updated);
            saveJsonToFile();
            jsonListView.refresh();
        });
    }

    // =========================================================================
    // WordEntry — JavaFX-property-backed model for the editable table
    // =========================================================================

    public static class WordEntry {
        private final StringProperty arabic = new SimpleStringProperty();
        private final StringProperty bangla = new SimpleStringProperty();

        WordEntry(String arabic, String bangla) {
            this.arabic.set(arabic);
            this.bangla.set(bangla);
        }

        public StringProperty arabicProperty() {
            return arabic;
        }

        public StringProperty banglaProperty() {
            return bangla;
        }

        public String getArabic() {
            return arabic.get();
        }

        public String getBangla() {
            return bangla.get();
        }

        public void setArabic(String v) {
            arabic.set(v);
        }

        public void setBangla(String v) {
            bangla.set(v);
        }
    }

    // =========================================================================
    // PDF viewer (unchanged from previous version)
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
        float pageWidthPt = doc.getPage(0).getMediaBox().getWidth();
        float pageWidthInches = pageWidthPt / 72f;
        return (float) ((viewportWidth / pageWidthInches) * zoom);
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
                } catch (Exception e) {
                    System.err.println("Re-render failed: " + e.getMessage());
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
        ProgressIndicator pi = new ProgressIndicator();
        VBox box = new VBox(12, pi, new Label("Rendering PDF…"));
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
            // Restore done status for pages that already have words
            masterList.forEach(p -> {
                if (p.getWordsinfo() != null && !p.getWordsinfo().isEmpty())
                    fetchStatusMap.put(p.getPageNumber(), FetchStatus.DONE);
            });
        } catch (IOException e) {
            System.err.println("Could not load JSON: " + e.getMessage());
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