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

    private static final String MODEL = "gemini-2.5-flash";
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
            + MODEL + ":generateContent?key=";

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiService(String apiKey) {
        this.apiKey = apiKey;
    }

    // ── Result record ─────────────────────────────────────────────────────────
    public record WordsResult(List<Map<String, String>> words, int totalWords) {
    }

    // =========================================================================
    // Main extraction method — send PDF page, get Arabic/Bangla word pairs back
    // =========================================================================
    public WordsResult extractWordsFromPdf(File pdfFile) throws Exception {
        // 1. Read and encode PDF
        byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
        String base64 = Base64.getEncoder().encodeToString(pdfBytes);

        // 2. Prompt — instructs Gemini to return strict JSON only
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

        // 3. Build Gemini request body using Maps (Jackson serialises them cleanly)
        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("mime_type", "application/pdf");
        inlineData.put("data", base64);

        Map<String, Object> pdfPart = Map.of("inline_data", inlineData);
        Map<String, Object> textPart = Map.of("text", prompt);

        Map<String, Object> content = Map.of("parts", List.of(pdfPart, textPart));
        Map<String, Object> body = Map.of("contents", List.of(content));

        String bodyJson = mapper.writeValueAsString(body);

        // 4. HTTP POST
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        // 5. Check HTTP status
        if (response.statusCode() != 200) {
            JsonNode err = mapper.readTree(response.body());
            String msg = err.path("error").path("message").asText(response.body());
            throw new RuntimeException("Gemini API error " + response.statusCode() + ": " + msg);
        }

        // 6. Extract the text content from the response
        JsonNode root = mapper.readTree(response.body());
        String rawText = root.at("/candidates/0/content/parts/0/text").asText("{}");

        // Strip markdown fences that Gemini occasionally adds despite instructions
        rawText = rawText.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        // 7. Parse the JSON payload
        JsonNode result = mapper.readTree(rawText);
        int totalWords = result.path("totalWords").asInt(0);

        List<Map<String, String>> words = mapper.readValue(
                result.path("words").toString(),
                new TypeReference<List<Map<String, String>>>() {
                });

        // Ensure required keys exist with defaults
        words.forEach(m -> {
            m.putIfAbsent("arabic", "");
            m.putIfAbsent("bangla", "");
            m.putIfAbsent("checked", "false");
        });

        if (totalWords == 0)
            totalWords = words.size();

        return new WordsResult(words, totalWords);
    }

    public List<String> extractArabicParts(String arabicWord) throws Exception {
        String prompt = """
                You are an Arabic script segmentation assistant.

                Input Arabic word: "%s"

                Task: Split this Arabic word into sequential parts following these strict rules:

                SPLITTING RULES:
                1. If the word has exactly 1 base alphabet letter → return the whole word as a single part (no split).
                2. If the word has exactly 2 base alphabet letters → split into exactly 2 parts.
                3. If the word has 3 or more base alphabet letters → split into multiple parts where each part contains a MAXIMUM of 3 base alphabet letters.
                4. Split moderately — not too many parts, not too few. Prefer 1-2 alphabet letters per part where possible.

                DIACRITICS / HARAKAT RULES:
                - NEVER split a harakat or diacritic mark away from its base letter.
                - Every harakat (like فتحة، ضمة، كسرة، سكون، شدة، تنوين etc.) must stay attached to the letter it belongs to.
                - Special joined forms (like لا) should be kept together as one unit.

                CONCATENATION RULE:
                - If you concatenate ALL parts in order you MUST get back the exact original word character by character.

                RETURN FORMAT:
                - Return ONLY a raw JSON array of strings. No markdown, no code fences, no explanation.
                - Example for word with 4 letters: ["part1", "part2"]
                - Example for single letter: ["letter"]

                Word to split: "%s"
                """
                .formatted(arabicWord, arabicWord);

        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(textPart));
        Map<String, Object> body = Map.of("contents", List.of(content));

        String bodyJson = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            JsonNode err = mapper.readTree(response.body());
            String msg = err.path("error").path("message").asText(response.body());
            throw new RuntimeException("Gemini API error " + response.statusCode() + ": " + msg);
        }

        JsonNode root = mapper.readTree(response.body());
        String rawText = root.at("/candidates/0/content/parts/0/text").asText("[]");

        // Strip any markdown fences
        rawText = rawText.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        // Parse array
        List<String> parts = mapper.readValue(rawText, new TypeReference<List<String>>() {
        });

        // Validation: concatenation must equal original word
        String rejoined = String.join("", parts);
        if (!rejoined.equals(arabicWord)) {
            System.err.println("Parts mismatch for [" + arabicWord + "]: got [" + rejoined + "] — retrying once");
            // One retry with stricter prompt note
            return extractArabicPartsRetry(arabicWord, rejoined);
        }

        return parts;
    }

    // ── Retry with explicit mismatch correction hint
    // ──────────────────────────────
    private List<String> extractArabicPartsRetry(String arabicWord, String wrongJoin) throws Exception {
        String prompt = """
                You are an Arabic script segmentation assistant.

                Input Arabic word: "%s"

                Task: Split this Arabic word into sequential parts following these strict rules:

                SPLITTING RULES:
                1. If the word has exactly 1 base alphabet letter → return the whole word as a single part (no split).
                2. If the word has exactly 2 base alphabet letters → split into exactly 2 parts.
                3. If the word has 3 or more base alphabet letters → split into multiple parts where each part contains a MAXIMUM of 3 base alphabet letters.
                4. Split moderately — not too many parts, not too few. Prefer 1-2 alphabet letters per part where possible.

                DIACRITICS / HARAKAT RULES:
                - NEVER split a harakat or diacritic mark away from its base letter.
                - Every harakat (like فتحة، ضمة، كسرة، سكون، شدة، تنوين etc.) must stay attached to the letter it belongs to.
                - Special joined forms (like لا) should be kept together as one unit.

                CONCATENATION RULE:
                - If you concatenate ALL parts in order you MUST get back the exact original word character by character.

                RETURN FORMAT:
                - Return ONLY a raw JSON array of strings. No markdown, no code fences, no explanation.
                - Example for word with 4 letters: ["part1", "part2"]
                - Example for single letter: ["letter"]

                Word to split: "%s"
                """
                .formatted(arabicWord, arabicWord);

        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(textPart));
        Map<String, Object> body = Map.of("contents", List.of(content));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // Fallback: return whole word as single part rather than crash
            System.err.println("Retry failed for [" + arabicWord + "] — returning whole word");
            return List.of(arabicWord);
        }

        JsonNode root = mapper.readTree(response.body());
        String rawText = root.at("/candidates/0/content/parts/0/text").asText("[]");
        rawText = rawText.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        List<String> parts = mapper.readValue(rawText, new TypeReference<List<String>>() {
        });

        // Final safety: if still wrong, return whole word
        String rejoined = String.join("", parts);
        if (!rejoined.equals(arabicWord)) {
            System.err.println("Retry still mismatched [" + arabicWord + "] — using whole word fallback");
            return List.of(arabicWord);
        }

        return parts;
    }

    // ── Verify result record
    // ──────────────────────────────────────────────────────
    public record VerifyResult(
            boolean matches, // did Gemini confirm the pair exists on the page?
            String correctedArabic, // Gemini's exact arabic (may differ if OCR was off)
            String correctedBangla, // Gemini's exact bangla
            String note // short explanation from Gemini
    ) {
    }

    // =========================================================================
    // Verify a single arabic/bangla pair against the PDF page
    // =========================================================================
    public VerifyResult verifyWordPair(File pdfFile, String arabic, String bangla) throws Exception {
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

        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("mime_type", "application/pdf");
        inlineData.put("data", base64);

        Map<String, Object> pdfPart = Map.of("inline_data", inlineData);
        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(pdfPart, textPart));
        Map<String, Object> body = Map.of("contents", List.of(content));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            JsonNode err = mapper.readTree(response.body());
            String msg = err.path("error").path("message").asText(response.body());
            throw new RuntimeException("Gemini API error " + response.statusCode() + ": " + msg);
        }

        JsonNode root = mapper.readTree(response.body());
        String rawText = root.at("/candidates/0/content/parts/0/text").asText("{}");
        rawText = rawText.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        JsonNode result = mapper.readTree(rawText);
        return new VerifyResult(
                result.path("matches").asBoolean(false),
                result.path("correctedArabic").asText(arabic),
                result.path("correctedBangla").asText(bangla),
                result.path("note").asText(""));
    }
}