package com.nsa.audiogenpremium;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.io.IOException;

public class App extends Application {

    private static Scene scene;

    @Override
    public void start(Stage stage) throws IOException {
        scene = new Scene(loadFXML("primary"), 960, 680);
        stage.setScene(scene);
        stage.setTitle("AudioGen Premium");
        stage.show();
    }

    // Show a loading screen for one frame, then swap to the real root
    public static void setRoot(String fxml) throws IOException {
        VBox loading = new VBox(12, new ProgressIndicator(), new Label("Loading…"));
        loading.setAlignment(Pos.CENTER);
        loading.setStyle("-fx-background-color: #ffffff;");
        scene.setRoot(loading);

        // Let the loading frame render, then load the real view
        Platform.runLater(() -> {
            try {
                scene.setRoot(loadFXML(fxml));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return loader.load();
    }

    public static void main(String[] args) {
        launch();
    }
}