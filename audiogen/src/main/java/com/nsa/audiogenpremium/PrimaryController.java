
package com.nsa.audiogenpremium;

import java.io.File; // Added this
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import javafx.fxml.FXML;
import javafx.stage.FileChooser; // Added this
import javafx.stage.Stage; // Added this
import javafx.scene.layout.VBox; // Assuming your root container is a VBox

public class PrimaryController {

    @FXML
    private VBox mainContainer; // Add an fx:id to your root element in FXML

    @FXML
    private void switchToSecondary() throws IOException {
        App.setRoot("secondary");
    }

    @FXML
    private void generateThroughWebsite() {
        // Start a background thread so the UI doesn't freeze
        // new Thread(() -> {
        System.setProperty("webdriver.chrome.driver",
                "src/main/resources/com/nsa/audiogenpremium/chromedriver.exe");

        ChromeOptions options = new ChromeOptions();
        // options.addArguments("--headless=new");
        WebDriver driver = new ChromeDriver(options);

        try {
            driver.get("https://lahajati.ai/en");
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

            // --- Selenium Logic ---
            WebElement scriptBox = wait
                    .until(ExpectedConditions.visibilityOfElementLocated(By.id("demo-text-input")));
            scriptBox.sendKeys("مَخَاضٌ");

            new Select(driver.findElement(By.id("dialect-select"))).selectByVisibleText("المصرية القاهرية");
            new Select(driver.findElement(By.id("performance-select"))).selectByVisibleText("تعليمي (واضح ومنظم)");

            String voiceName = "بدر";
            wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//div[@role='radio' and contains(@aria-label, '" + voiceName + "')]"))).click();

            driver.findElement(By.cssSelector("button[type='submit'][aria-label*='Generate']")).click();

            // Wait for the base64 data to populate
            WebElement downloadLink = wait
                    .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("a.audio-download-button")));
            wait.until(d -> {
                String href = downloadLink.getAttribute("href");
                return href != null && href.contains("base64");
            });

            // --- Efficient Memory Handling ---
            StringBuilder audioDataBuilder = new StringBuilder(downloadLink.getAttribute("href"));
            int commaIndex = audioDataBuilder.indexOf(",");

            if (commaIndex != -1) {
                // DELETE the metadata prefix to save memory instead of creating a new substring
                audioDataBuilder.delete(0, commaIndex + 1);

                // Decode the remaining content in the builder
                byte[] audioBytes = Base64.getDecoder().decode(audioDataBuilder.toString());

                // --- Switch back to UI Thread for the FileChooser ---
                javafx.application.Platform.runLater(() -> {
                    handleSaveFile(audioBytes);
                });
            }
            audioDataBuilder.setLength(0);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
        // }).start(); // Don't forget to start the thread!
    }

    private void handleSaveFile(byte[] audioBytes) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Audio File");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV Files", "*.wav"));

        // Get the window from the main layout (replace 'mainContainer' with your FXML
        // ID)
        File file = fileChooser.showSaveDialog(null);

        if (file != null) {
            try {
                Files.write(file.toPath(), audioBytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}