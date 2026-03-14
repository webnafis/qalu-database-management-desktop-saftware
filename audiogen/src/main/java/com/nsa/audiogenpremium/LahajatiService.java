package com.nsa.audiogenpremium;

import javafx.application.Platform;
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

    // Only used when no proxies configured
    private WebDriver driver;

    // Thrown specifically when proxy connected but audio fetch timed out → green
    private static class AudioFetchException extends Exception {
        AudioFetchException(String msg) {
            super(msg);
        }
    }

    // ── Driver creation ────────────────────────────────────────────────────
    private WebDriver createDriver(String proxy) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        if (proxy != null)
            options.addArguments("--proxy-server=http://" + proxy);
        return new ChromeDriver(options);
    }

    private void initDriver() {
        if (driver != null)
            return;
        driver = createDriver(null);
        AppLogger.info("[Lahajati] ChromeDriver started (headless, no proxy)");
    }

    // ── Public entry point ─────────────────────────────────────────────────
    public String generateAudio(String arabicText, File outputDir) throws Exception {
        if (arabicText == null || arabicText.isBlank())
            throw new IllegalArgumentException("Arabic text is empty");
        outputDir.mkdirs();

        List<String> proxies = ProxyManager.load();

        if (proxies.isEmpty()) {
            // No proxies — use persistent driver directly
            initDriver();
            AppLogger.info("[Lahajati] No proxies configured — using direct connection");
            return runGeneration(arabicText, outputDir, driver);
        }

        // ── Iterate proxies ────────────────────────────────────────────────
        for (int i = 0; i < proxies.size(); i++) {
            String proxy = proxies.get(i);
            AppLogger.info("[Lahajati] Trying proxy " + (i + 1) + "/" + proxies.size() + ": " + proxy);

            WebDriver proxyDriver = null;
            try {
                proxyDriver = createDriver(proxy);
                String result = runGeneration(arabicText, outputDir, proxyDriver);

                // ── Success — mark green ───────────────────────────────────
                ProxyManager.setStatus(proxy, "green");
                AppLogger.success("[Lahajati] Proxy " + proxy + " worked ✓ → marked green");
                Platform.runLater(ProxyManagerWindow::refresh);
                return result;

            } catch (AudioFetchException e) {
                // Proxy connected to site but audio generation timed out
                // → proxy is valid (green), but site failed this round
                ProxyManager.setStatus(proxy, "green");
                AppLogger.warn("[Lahajati] Proxy " + proxy
                        + " connected but audio timed out → marked GREEN, trying next proxy");
                AppLogger.warn("[Lahajati] Audio error detail: " + e.getMessage());
                Platform.runLater(ProxyManagerWindow::refresh);

            } catch (Exception e) {
                // Proxy itself failed — connection refused, network error, etc.
                ProxyManager.setStatus(proxy, "red");
                AppLogger.error("[Lahajati] Proxy " + proxy
                        + " failed to connect → marked RED, trying next proxy");
                AppLogger.error("[Lahajati] Proxy error detail: " + e.getMessage());
                Platform.runLater(ProxyManagerWindow::refresh);

            } finally {
                if (proxyDriver != null) {
                    try {
                        proxyDriver.quit();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        throw new RuntimeException(
                "All " + proxies.size() + " proxies exhausted — could not generate audio for: " + arabicText);
    }

    // ── Core generation logic (uses passed driver) ─────────────────────────
    private String runGeneration(String arabicText, File outputDir, WebDriver wd)
            throws Exception {

        AppLogger.info("[Lahajati] Generating audio for: " + arabicText);
        wd.get("https://lahajati.ai/en");
        WebDriverWait wait = new WebDriverWait(wd, Duration.ofSeconds(30));

        // Type text
        WebElement scriptBox = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("demo-text-input")));
        scriptBox.clear();
        scriptBox.sendKeys(arabicText);
        AppLogger.info("[Lahajati] Text entered");

        // Dialect
        try {
            Select dialectSelect = new Select(wd.findElement(By.id("dialect-select")));
            for (WebElement opt : dialectSelect.getOptions()) {
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

        // Performance
        try {
            Select perfSelect = new Select(wd.findElement(By.id("performance-select")));
            for (WebElement opt : perfSelect.getOptions()) {
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

        // Click generate
        wd.findElement(By.cssSelector("button[type='submit'][aria-label*='Generate']")).click();
        AppLogger.info("[Lahajati] Generate clicked");

        // Wait for download link — if this times out it's an AudioFetchException
        try {
            WebElement downloadLink = new WebDriverWait(wd, Duration.ofSeconds(30)).until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("a.audio-download-button")));

            new WebDriverWait(wd, Duration.ofSeconds(60)).until(d -> {
                String href = downloadLink.getAttribute("href");
                return href != null && href.contains("base64");
            });

            // Decode base64
            StringBuilder audioDataBuilder = new StringBuilder(
                    downloadLink.getAttribute("href"));
            int commaIndex = audioDataBuilder.indexOf(",");
            if (commaIndex == -1)
                throw new AudioFetchException("No comma in href — unexpected format");

            audioDataBuilder.delete(0, commaIndex + 1);
            byte[] audioBytes = Base64.getDecoder().decode(audioDataBuilder.toString());
            audioDataBuilder.setLength(0);

            // Save
            File outFile = new File(outputDir, "audio_" + System.currentTimeMillis() + ".wav");
            Files.write(outFile.toPath(), audioBytes);
            AppLogger.success("[Lahajati] Saved: " + outFile.getName());
            return outFile.getAbsolutePath();

        } catch (AudioFetchException e) {
            throw e; // rethrow as-is
        } catch (Exception e) {
            // Any wait timeout here = audio fetch failed = green proxy
            throw new AudioFetchException(e.getMessage());
        }
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