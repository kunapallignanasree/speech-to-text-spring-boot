package com.example.speech;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/speech")
public class whisperController {

    @Value("${openai.api.key}")
    private String apiKey;

    @PostMapping("/transcribe")
    public ResponseEntity<String> transcribeAudio(@RequestParam("file") MultipartFile file) {
        try {
            File tempFile = File.createTempFile("audio", ".webm");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(file.getBytes());
            }

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();

            // 1. Audio Transcription via Groq Whisper
            okhttp3.RequestBody fileBody = okhttp3.RequestBody.create(
                    tempFile,
                    okhttp3.MediaType.parse("audio/webm")
            );

            String whisperPrompt = "Hindi (खाना, नहीं, पागल, क्या, नमस्ते, कैसे हो), Telugu (నమస్కారం, తిన్నావా, లేదు), English (Hi, Hello, Good morning).";

            okhttp3.RequestBody requestBody = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", tempFile.getName(), fileBody)
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("prompt", whisperPrompt)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(requestBody)
                    .build();

            String rawTranscription = "";
            try (okhttp3.Response response = client.newCall(request).execute()) {
                tempFile.delete();
                if (response.isSuccessful() && response.body() != null) {
                    rawTranscription = response.body().string();
                } else {
                    return ResponseEntity.status(response.code())
                            .body("Error from Groq Whisper API: " + response.message());
                }
            }

            String transcribedText = extractJsonFieldValue(rawTranscription, "text");
            if (transcribedText.isEmpty()) {
                transcribedText = rawTranscription;
            }

            // 2. Pure Script Formatting via Llama-3 (Strict Verbatim Transcriber)
            String correctedScriptText = convertToNativeScripts(client, transcribedText);
            correctedScriptText = correctedScriptText.replace("\"", "\\\"").trim();

            return ResponseEntity.ok("{\"text\": \"" + correctedScriptText + "\"}");

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error processing audio file.");
        }
    }

    private String convertToNativeScripts(okhttp3.OkHttpClient client, String input) {
        try {
            String systemPrompt = """
                CRITICAL INSTRUCTION: You are a strict VERBATIM TRANSCRIBER, NOT A CHATBOT. 
                Do NOT reply to the user. Do NOT answer questions. Do NOT initiate conversation. 
                Your ONLY task is to re-write the exact spoken input into native scripts word-for-word:

                1. Keep English words in English letters (e.g., "Hi", "Good morning", "How are you").
                2. Convert Telugu words into Telugu script (e.g., "నమస్కారం", "తిన్నావా", "ఎలా ఉన్నారు").
                3. Convert Hindi words into Devanagari Hindi script (e.g., "नमस्ते", "कैसे हो", "खाना", "पागल").

                Examples:
                - Input: Kaise ho
                  Output: कैसे हो (DO NOT reply "main theek hoon")
                - Input: Hi tinnava
                  Output: Hi తిన్నావా
                - Input: Namaste pagal
                  Output: नमस्ते पागल

                OUTPUT ONLY THE EXACT TRANSCRIBED WORDS IN NATIVE SCRIPTS. NO REPLIES.
                """;

            String cleanInput = input.replace("\"", "\\\"").replace("\n", " ");

            String jsonPayload = """
                {
                  "model": "llama-3.1-8b-instant",
                  "temperature": 0.0,
                  "messages": [
                    {"role": "system", "content": "%s"},
                    {"role": "user", "content": "%s"}
                  ]
                }
                """.formatted(systemPrompt.replace("\"", "\\\"").replace("\n", "\\n"), cleanInput);

            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    jsonPayload,
                    okhttp3.MediaType.parse("application/json")
            );

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String resStr = response.body().string();
                    String content = extractJsonFieldValue(resStr, "content");
                    if (!content.isEmpty()) {
                        return content;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return input;
    }

    private String extractJsonFieldValue(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"(.*?)\"(?=[,}])", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"").trim();
        }
        return "";
    }
}