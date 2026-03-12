module com.nsa.audiogenpremium {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing; // ← ADD THIS (for SwingFXUtils)
    requires java.desktop; // ← needed for BufferedImage
    requires javafx.media; // ← for audio playback

    requires org.seleniumhq.selenium.api;
    requires org.seleniumhq.selenium.chrome_driver;
    requires org.seleniumhq.selenium.support;
    requires org.seleniumhq.selenium.remote_driver;
    requires org.seleniumhq.selenium.http;
    requires dev.failsafe.core;
    requires com.google.common;

    requires java.net.http; // ← for HttpClient

    requires org.apache.pdfbox;
    requires com.fasterxml.jackson.databind;
    requires javafx.graphics;

    opens com.nsa.audiogenpremium to javafx.fxml, com.fasterxml.jackson.databind;

    exports com.nsa.audiogenpremium;
}