package com.nsa.audiogenpremium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

public class GeminiService {

        // ── Text generation models ─────────────────────────────────────────────────
        // Replace the TextModel enum inside GeminiService.java

        public enum TextModel {
                PRO("gemini-2.5-pro", "Gemini 2.5 Pro   [5 RPM / 100 RPD]"),
                FLASH("gemini-2.5-flash", "Gemini 2.5 Flash  [10 RPM / 250 RPD]"),
                FLASH_LITE("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite [15 RPM / 1K RPD]"),
                FLASH_2("gemini-2.0-flash", "Gemini 2.0 Flash  [15 RPM / 1.5K RPD]"),
                FLASH_8B("gemini-2.0-flash-8b", "Gemini 2.0 Flash-8B [15 RPM / 1.5K RPD]");

                public final String apiName;
                public final String displayName;

                TextModel(String apiName, String displayName) {
                        this.apiName = apiName;
                        this.displayName = displayName;
                }

                @Override
                public String toString() {
                        return displayName;
                }
        }

        // ── Audio TTS model ────────────────────────────────────────────────────────
        // gemini-2.5-flash-preview-tts uses standard REST — correct for file
        // generation.
        // The "native-audio" model requires Live API (WebSocket) — not suitable here.
        private static final String TTS_MODEL = "gemini-2.5-flash-preview-tts";

        private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

        private final String apiKey;
        private TextModel textModel = TextModel.FLASH; // default
        private final HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();
        private final ObjectMapper mapper = new ObjectMapper();

        public GeminiService(String apiKey) {
                this.apiKey = apiKey;
        }

        public void setTextModel(TextModel m) {
                this.textModel = m;
        }

        public TextModel getTextModel() {
                return textModel;
        }

        // ── URL builders ───────────────────────────────────────────────────────────
        private String textUrl() {
                return BASE_URL + textModel.apiName + ":generateContent?key=" + apiKey;
        }

        private String ttsUrl() {
                return BASE_URL + TTS_MODEL + ":generateContent?key=" + apiKey;
        }

        // =========================================================================
        // Result records
        // =========================================================================
        public record WordsResult(List<Map<String, String>> words, int totalWords) {
        }

        public record VerifyResult(
                        boolean matches,
                        String correctedArabic,
                        String correctedBangla,
                        String note) {
        }

        // =========================================================================
        // Extract Arabic/Bangla pairs from PDF page
        // =========================================================================
        public WordsResult extractWordsFromPdf(File pdfFile) throws Exception {
                byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
                String base64 = Base64.getEncoder().encodeToString(pdfBytes);

                String prompt = """
                                You are an Arabic-Bangla text extraction assistant.
                                This is a single page from an Arabic-Bangla educational PDF.

                                Task:
                                - Find every place on this page where an Arabic word/phrase and its Bangla
                                  translation or meaning appear TOGETHER (as a pair).
                                - Extract ONLY those pairs that are explicitly printed on the page.
                                - Count the total number of pairs extracted.

                                Rules:
                                - DO NOT translate, infer, or guess any meaning yourself.
                                - DO NOT include Arabic text that has no Bangla counterpart on the page.
                                - DO NOT include Bangla text that has no Arabic counterpart on the page.
                                - Copy the Arabic and Bangla text EXACTLY as printed — preserve all diacritics,
                                  harakat, punctuation, and Unicode characters precisely.
                                - Return ONLY a raw JSON object. No markdown, no code fences, no explanation.
                                - Use this exact structure:
                                {
                                  "totalWords": <integer>,
                                  "words": [
                                    { "arabic": "<exact arabic text from page>", "bangla": "<exact bangla text from page>" },
                                    ...
                                  ]
                                }
                                - If no Arabic-Bangla pairs are found on this page, return:
                                { "totalWords": 0, "words": [] }
                                """;

                String rawText = sendTextRequest(buildPdfBody(base64, prompt), textUrl());

                JsonNode result = mapper.readTree(rawText);
                int totalWords = result.path("totalWords").asInt(0);

                List<Map<String, String>> words = mapper.readValue(
                                result.path("words").toString(),
                                new TypeReference<List<Map<String, String>>>() {
                                });

                words.forEach(m -> {
                        m.putIfAbsent("arabic", "");
                        m.putIfAbsent("bangla", "");
                        m.putIfAbsent("checked", "false");
                });

                if (totalWords == 0)
                        totalWords = words.size();
                return new WordsResult(words, totalWords);
        }

        // =========================================================================
        // Verify a single Arabic/Bangla pair against the PDF page
        // =========================================================================
        public VerifyResult verifyWordPair(File pdfFile, String arabic, String bangla)
                        throws Exception {
                byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
                String base64 = Base64.getEncoder().encodeToString(pdfBytes);

                String prompt = """
                                You are an Arabic-Bangla text verification assistant.
                                This is a single PDF page.

                                I have extracted this pair from the page:
                                  Arabic : "%s"
                                  Bangla : "%s"

                                Task:
                                1. Check whether this Arabic text actually appears on the page.
                                2. Check whether the Bangla text paired with it on the page matches what I provided.
                                3. If either is slightly wrong (wrong harakat, extra letter, wrong Bangla),
                                   provide the corrected exact versions as they appear on the page.

                                Rules:
                                - Copy text EXACTLY as printed — preserve all harakat and Unicode.
                                - DO NOT translate or infer anything yourself.
                                - Return ONLY a raw JSON object. No markdown, no code fences, no explanation.
                                - Use this exact structure:
                                {
                                  "matches": true | false,
                                  "correctedArabic": "<exact arabic from page, or original if correct>",
                                  "correctedBangla": "<exact bangla from page, or original if correct>",
                                  "note": "<one short sentence explaining your finding>"
                                }
                                """.formatted(arabic, bangla);

                String rawText = sendTextRequest(buildPdfBody(base64, prompt), textUrl());

                JsonNode r = mapper.readTree(rawText);
                return new VerifyResult(
                                r.path("matches").asBoolean(false),
                                r.path("correctedArabic").asText(arabic),
                                r.path("correctedBangla").asText(bangla),
                                r.path("note").asText(""));
        }

        // =========================================================================
        // Split Arabic word into parts
        // =========================================================================
        public List<String> extractArabicParts(String arabicWord) throws Exception {
                String prompt = buildPartsPrompt(arabicWord);

                Map<String, Object> body = Map.of("contents",
                                List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

                String rawText = sendTextRequest(body, textUrl());
                List<String> parts = mapper.readValue(rawText,
                                new TypeReference<List<String>>() {
                                });

                String joined = String.join("", parts);
                if (!joined.equals(arabicWord)) {
                        System.err.println("Parts mismatch for [" + arabicWord
                                        + "]: got [" + joined + "] — retrying");
                        return extractArabicPartsRetry(arabicWord);
                }
                return parts;
        }

        private List<String> extractArabicPartsRetry(String arabicWord)
                        throws Exception {
                String retryHeader = """
                                ⚠ CRITICAL CORRECTION REQUIRED:
                                A previous attempt failed to split "%s" correctly.
                                The joined result of all parts MUST match the original EXACTLY, character by character.
                                Fix this before answering.

                                """.formatted(arabicWord);

                String prompt = retryHeader + buildPartsPrompt(arabicWord);

                Map<String, Object> body = Map.of("contents",
                                List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

                try {
                        String rawText = sendTextRequest(body, textUrl());
                        List<String> parts = mapper.readValue(rawText,
                                        new TypeReference<List<String>>() {
                                        });
                        if (String.join("", parts).equals(arabicWord))
                                return parts;
                } catch (Exception ignored) {
                }

                System.err.println("Retry still failed for [" + arabicWord + "] — whole word fallback");
                return List.of(arabicWord);
        }

        private String buildPartsPrompt(String arabicWord) {
                return """
                                You are an Arabic script segmentation assistant.

                                Input Arabic word: "%s"

                                Task: Split this Arabic word into sequential parts following these strict rules:

                                SPLITTING RULES:
                                1. If the word has exactly 1 base alphabet letter → return the whole word as a single part (no split).
                                2. If the word has exactly 2 base alphabet letters → split into exactly 2 parts.
                                3. If the word has 3 or more base alphabet letters → split into multiple parts where
                                   each part contains a MAXIMUM of 3 base alphabet letters.
                                4. Split moderately — not too many parts, not too few.
                                   Prefer 1-2 alphabet letters per part where possible.

                                DIACRITICS / HARAKAT RULES:
                                - NEVER split a harakat or diacritic mark away from its base letter.
                                - Every harakat (فتحة، ضمة، كسرة، سكون، شدة، تنوين etc.) must stay attached
                                  to the letter it belongs to.
                                - Special joined forms (like لا) should be kept together as one unit.

                                CONCATENATION RULE:
                                - If you concatenate ALL parts in order you MUST get back the exact original
                                  word character by character.

                                RETURN FORMAT:
                                - Return ONLY a raw JSON array of strings. No markdown, no code fences, no explanation.
                                - Example for 4-letter word: ["part1", "part2"]
                                - Example for single letter: ["letter"]

                                Word to split: "%s"
                                """
                                .formatted(arabicWord, arabicWord);
        }

        // =========================================================================
        // Generate Arabic TTS audio — saves file, returns absolute path
        // =========================================================================
        public String generateArabicAudio(String arabicText, File outputDir) throws Exception {
                outputDir.mkdirs();

                // Only one voice — retry same voice on transient failures
                String voice = "Charon";
                int maxRetries = 5;
                long[] retryDelaysMs = { 2000, 4000, 8000, 15000, 30000 }; // backoff

                Exception lastError = null;

                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                        try {
                                AppLogger.info("TTS attempt " + attempt + "/" + maxRetries
                                                + " voice: " + voice + " | text: " + arabicText);

                                String path = attemptTts(arabicText, voice, outputDir);

                                AppLogger.success("TTS success on attempt " + attempt
                                                + " → " + new File(path).getName());
                                return path;

                        } catch (RateLimitException rle) {
                                // Wait exact time Gemini says, then continue loop
                                long waitMs = (rle.retryAfterSeconds + 3) * 1000L;
                                AppLogger.warn("TTS rate limited — waiting " + rle.retryAfterSeconds
                                                + "s before attempt " + (attempt + 1));
                                long endAt = System.currentTimeMillis() + waitMs;
                                while (System.currentTimeMillis() < endAt) {
                                        long secsLeft = (endAt - System.currentTimeMillis()) / 1000;
                                        AppLogger.info("Rate limit cooldown: " + secsLeft + "s remaining…");
                                        Thread.sleep(Math.min(5000, endAt - System.currentTimeMillis()));
                                }
                                lastError = rle;

                        } catch (VoiceUnsupportedException vue) {
                                // finishReason=OTHER — transient, just wait and retry same voice
                                if (attempt < maxRetries) {
                                        long delay = retryDelaysMs[attempt - 1];
                                        AppLogger.warn("TTS got OTHER on attempt " + attempt
                                                        + " — retrying in " + (delay / 1000) + "s (transient glitch)");
                                        Thread.sleep(delay);
                                }
                                lastError = vue;

                        } catch (Exception e) {
                                if (attempt < maxRetries) {
                                        long delay = retryDelaysMs[attempt - 1];
                                        AppLogger.warn("TTS attempt " + attempt + " failed: "
                                                        + e.getMessage() + " — retrying in " + (delay / 1000) + "s");
                                        Thread.sleep(delay);
                                }
                                lastError = e;
                        }
                }

                throw new RuntimeException("TTS failed after " + maxRetries
                                + " attempts for: [" + arabicText + "]. Last error: "
                                + (lastError != null ? lastError.getMessage() : "unknown"));
        }

        private String attemptTts(String arabicText, String voiceName, File outputDir)
                        throws Exception {

                Map<String, Object> prebuiltVoice = Map.of("voiceName", voiceName);
                Map<String, Object> voiceConfig = Map.of("prebuiltVoiceConfig", prebuiltVoice);
                Map<String, Object> speechConfig = Map.of("voiceConfig", voiceConfig);

                Map<String, Object> genConfig = new LinkedHashMap<>();
                genConfig.put("responseModalities", List.of("AUDIO"));
                genConfig.put("speechConfig", speechConfig);

                Map<String, Object> textPart = Map.of("text", arabicText);
                Map<String, Object> content = Map.of("parts", List.of(textPart));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("contents", List.of(content));
                body.put("generationConfig", genConfig);

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(ttsUrl()))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(120))
                                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                                .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 500 || response.statusCode() == 503) {
                        throw new RuntimeException("Server error " + response.statusCode());
                }
                if (response.statusCode() != 200) {
                        JsonNode err = mapper.readTree(response.body());
                        String msg = err.path("error").path("message").asText(response.body());
                        throw new RuntimeException("TTS error " + response.statusCode() + ": " + msg);
                }

                JsonNode root = mapper.readTree(response.body());
                String finishReason = root.at("/candidates/0/finishReason").asText("unknown");
                AppLogger.info("TTS [" + voiceName + "] finishReason: " + finishReason);

                JsonNode audioPart = root.at("/candidates/0/content/parts/0/inlineData");
                if (audioPart.isMissingNode()) {
                        throw new RuntimeException("No audio data. finishReason=" + finishReason);
                }

                String mimeType = audioPart.path("mimeType").asText("audio/wav");
                String base64Data = audioPart.path("data").asText("");
                byte[] rawBytes = Base64.getDecoder().decode(base64Data);

                AppLogger.info("TTS mimeType: " + mimeType + " rawBytes: " + rawBytes.length);

                // Gemini TTS returns raw signed 16-bit PCM at 24000 Hz, 1 channel.
                // We must wrap it in a WAV header — otherwise no player can open it.
                byte[] wavBytes = rawBytes;
                if (mimeType.contains("L16") || mimeType.contains("pcm")
                                || mimeType.contains("wav")) {
                        int sampleRate = parseSampleRate(mimeType, 24000);
                        wavBytes = addWavHeader(rawBytes, sampleRate, 1, 16);
                }

                File outFile = new File(outputDir, "audio_" + System.currentTimeMillis() + ".wav");
                Files.write(outFile.toPath(), wavBytes);

                int ttsTokens = root.at("/usageMetadata/totalTokenCount").asInt(50);
                TokenTracker.get().recordRequest("gemini-2.5-flash-preview-tts", ttsTokens);

                return outFile.getAbsolutePath();
        }

        // ── Parse sample rate from mime type like "audio/L16;rate=24000" ─────────────
        private int parseSampleRate(String mimeType, int defaultRate) {
                try {
                        for (String part : mimeType.split(";")) {
                                part = part.trim();
                                if (part.startsWith("rate="))
                                        return Integer.parseInt(part.substring(5).trim());
                        }
                } catch (Exception ignored) {
                }
                return defaultRate;
        }

        // ── Write a standard 44-byte PCM WAV header then the raw PCM data
        // ─────────────
        private byte[] addWavHeader(byte[] pcmData, int sampleRate,
                        int channels, int bitsPerSample) throws Exception {
                int byteRate = sampleRate * channels * bitsPerSample / 8;
                int blockAlign = channels * bitsPerSample / 8;
                int dataSize = pcmData.length;
                int chunkSize = 36 + dataSize;

                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream dos = new java.io.DataOutputStream(out);

                // RIFF header
                dos.writeBytes("RIFF");
                dos.writeInt(Integer.reverseBytes(chunkSize));
                dos.writeBytes("WAVE");

                // fmt chunk
                dos.writeBytes("fmt ");
                dos.writeInt(Integer.reverseBytes(16)); // chunk size
                dos.writeShort(Short.reverseBytes((short) 1)); // PCM format
                dos.writeShort(Short.reverseBytes((short) channels));
                dos.writeInt(Integer.reverseBytes(sampleRate));
                dos.writeInt(Integer.reverseBytes(byteRate));
                dos.writeShort(Short.reverseBytes((short) blockAlign));
                dos.writeShort(Short.reverseBytes((short) bitsPerSample));

                // data chunk
                dos.writeBytes("data");
                dos.writeInt(Integer.reverseBytes(dataSize));
                dos.write(pcmData);
                dos.flush();

                return out.toByteArray();
        }
        // =========================================================================
        // Shared HTTP / request helpers
        // =========================================================================

        /** Build a request body that includes an inline PDF + a text prompt. */
        private Map<String, Object> buildPdfBody(String base64Pdf, String prompt) {
                Map<String, Object> inlineData = new LinkedHashMap<>();
                inlineData.put("mime_type", "application/pdf");
                inlineData.put("data", base64Pdf);

                Map<String, Object> pdfPart = Map.of("inline_data", inlineData);
                Map<String, Object> textPart = Map.of("text", prompt);
                Map<String, Object> content = Map.of("parts", List.of(pdfPart, textPart));
                return Map.of("contents", List.of(content));
        }

        /**
         * Send a request to the given URL and return the stripped text from the
         * first candidate's first part.
         */
        private String sendTextRequest(Map<String, Object> body, String url) throws Exception {
                String bodyJson = mapper.writeValueAsString(body);
                int estimatedInputTokens = bodyJson.length() / 4; // rough estimate

                HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(120))
                                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                                .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                        JsonNode err = mapper.readTree(response.body());
                        String msg = err.path("error").path("message").asText(response.body());
                        AppLogger.error("Gemini [" + textModel.apiName + "] error: " + msg);
                        throw new RuntimeException("Gemini API error " + response.statusCode() + ": " + msg);
                }

                JsonNode root = mapper.readTree(response.body());

                // Use actual token counts if Gemini returns them
                int inputTokens = root.at("/usageMetadata/promptTokenCount").asInt(estimatedInputTokens);
                int outputTokens = root.at("/usageMetadata/candidatesTokenCount").asInt(50);
                int totalTokens = inputTokens + outputTokens;

                TokenTracker.get().recordRequest(textModel.apiName, totalTokens);
                AppLogger.info("Gemini [" + textModel.apiName + "] — "
                                + totalTokens + " tokens used | RPD left: "
                                + TokenTracker.get().remainingRpd(textModel.apiName));

                String rawText = root.at("/candidates/0/content/parts/0/text").asText("{}");
                return rawText.replaceAll("(?s)```json\\s*", "")
                                .replaceAll("(?s)```\\s*", "")
                                .trim();
        }

        // ── Add these anywhere inside GeminiService class (not inside any method)
        // ─────

        private static class RateLimitException extends Exception {
                final long retryAfterSeconds;

                RateLimitException(String message, long retryAfterSeconds) {
                        super(message);
                        this.retryAfterSeconds = retryAfterSeconds;
                }
        }

        private static class VoiceUnsupportedException extends Exception {
                VoiceUnsupportedException(String msg) {
                        super(msg);
                }
        }

        // private long parseRetryAfter(String errorMessage) {
        // try {
        // java.util.regex.Matcher m = java.util.regex.Pattern
        // .compile("retry in ([0-9]+(?:\\.[0-9]+)?)s")
        // .matcher(errorMessage);
        // if (m.find())
        // return (long) Math.ceil(Double.parseDouble(m.group(1)));
        // } catch (Exception ignored) {
        // }
        // return 35L;
        // }
}