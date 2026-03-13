package com.nsa.audiogenpremium;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public class LahajatiService {

    private WebDriver driver;

    private void initDriver() {
        if (driver != null)
            return;

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));

        driver = new ChromeDriver(options);
        AppLogger.info("[Lahajati] ChromeDriver started (headless)");
    }

    public String generateAudio(String arabicText, File outputDir) throws Exception {
        if (arabicText == null || arabicText.isBlank())
            throw new IllegalArgumentException("Arabic text is empty");
        outputDir.mkdirs();
        initDriver();

        AppLogger.info("[Lahajati] Generating audio for: " + arabicText);

        driver.get("https://lahajati.ai/en");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        // ── Type text ──────────────────────────────────────────────────────
        WebElement scriptBox = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("demo-text-input")));
        scriptBox.clear();
        scriptBox.sendKeys(arabicText);
        AppLogger.info("[Lahajati] Text entered");

        // ── Dialect ────────────────────────────────────────────────────────
        try {
            Select dialectSelect = new Select(driver.findElement(By.id("dialect-select")));
            List<WebElement> opts = dialectSelect.getOptions();
            for (WebElement opt : opts) {
                String val = opt.getAttribute("value");
                if (val != null && !val.isBlank()) {
                    dialectSelect.selectByValue(val);
                    AppLogger.info("[Lahajati] Dialect selected: " + opt.getText());
                    break;
                }
            }
        } catch (Exception e) {
            AppLogger.warn("[Lahajati] Could not set dialect: " + e.getMessage());
        }

        // ── Performance style ──────────────────────────────────────────────
        try {
            Select perfSelect = new Select(driver.findElement(By.id("performance-select")));
            List<WebElement> opts = perfSelect.getOptions();
            for (WebElement opt : opts) {
                String val = opt.getAttribute("value");
                if (val != null && !val.isBlank()) {
                    perfSelect.selectByValue(val);
                    AppLogger.info("[Lahajati] Performance selected: " + opt.getText());
                    break;
                }
            }
        } catch (Exception e) {
            AppLogger.warn("[Lahajati] Could not set performance: " + e.getMessage());
        }

        // ── Click Generate ─────────────────────────────────────────────────
        driver.findElement(
                By.cssSelector("button[type='submit'][aria-label*='Generate']")).click();
        AppLogger.info("[Lahajati] Generate clicked");

        // ── Wait for download link with base64 (exactly your working approach) ──
        WebElement downloadLink = wait.until(
                ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("a.audio-download-button")));

        new WebDriverWait(driver, Duration.ofSeconds(60)).until(d -> {
            String href = downloadLink.getAttribute("href");
            return href != null && href.contains("base64");
        });

        // ── Decode base64 (memory-efficient, exactly your approach) ────────
        StringBuilder audioDataBuilder = new StringBuilder(
                downloadLink.getAttribute("href"));
        int commaIndex = audioDataBuilder.indexOf(",");

        if (commaIndex == -1)
            throw new RuntimeException("[Lahajati] Unexpected href format — no comma found");

        audioDataBuilder.delete(0, commaIndex + 1);
        byte[] audioBytes = Base64.getDecoder().decode(audioDataBuilder.toString());
        audioDataBuilder.setLength(0); // free memory

        // ── Save with arabic word as filename ──────────────────────────────
        // Strip characters that are invalid in filenames
        String safeName = arabicText.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        File outFile = new File(outputDir, safeName + ".wav");

        Files.write(outFile.toPath(), audioBytes);
        AppLogger.success("[Lahajati] Saved: " + outFile.getName());
        return outFile.getAbsolutePath();
    }

    public void quitDriver() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception ignored) {
            }
            driver = null;
            AppLogger.info("[Lahajati] ChromeDriver closed");
        }
    }
}