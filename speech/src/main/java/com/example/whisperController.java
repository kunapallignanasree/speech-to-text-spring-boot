package com.example;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private TranscriptionRepository transcriptionRepository;

    @PostMapping("/transcribe")
    public ResponseEntity<String> transcribeAudio(@RequestParam("file") MultipartFile file) {
        try {
            File tempFile = File.createTempFile("audio", ".webm");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(file.getBytes());
            }

            OkHttpClient client = new OkHttpClient();

            // 1. Groq Whisper Transcription
            MediaType mediaTypeWebm = MediaType.parse("audio/webm");
            RequestBody fileBody = RequestBody.create(mediaTypeWebm, tempFile);

            String whisperPrompt = "Hi, Hello, Good morning. తిన్నారా, తిన్నావా, నమస్కారం, బాగున్నారా, ఎలా ఉన్నారు. नमस्ते, कैसे हो, खाना खाया.";

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tempFile.getName(), fileBody)
                    .addFormDataPart("model", "whisper-large-v3")
                    .addFormDataPart("prompt", whisperPrompt)
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(requestBody)
                    .build();

            String rawTranscription = "";
            try (Response response = client.newCall(request).execute()) {
                tempFile.delete();
                String resBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    rawTranscription = resBody;
                } else {
                    return ResponseEntity.status(response.code())
                            .body("Error from Groq Whisper API: " + resBody);
                }
            }

            String transcribedText = extractJsonFieldValue(rawTranscription, "text");
            if (transcribedText.isEmpty()) {
                transcribedText = rawTranscription;
            }

            // 2. Strict Transliteration via Llama-3
            String correctedScriptText = convertToNativeScripts(client, transcribedText);

            if (transcriptionRepository != null) {
                transcriptionRepository.save(new Transcription(correctedScriptText));
            }

            String jsonSafeText = correctedScriptText.replace("\\", "\\\\").replace("\"", "\\\"").trim();
            return ResponseEntity.ok("{\"text\": \"" + jsonSafeText + "\"}");

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error processing audio file.");
        }
    }

    private String convertToNativeScripts(OkHttpClient client, String input) {
        try {
            String systemPrompt = """
                You are an expert multilingual speech-to-text post-processor.
                Your task is to correct Romanized Indian language words into their native scripts while strictly preserving pure English words.

                STRICT RULES:
                1. Pure English words (like "Hi", "Hello", "Good morning", "Good night", "How are you", "Sir", "Please", "Thank you") MUST stay in English Latin letters.
                2. Telugu words written in English/Latin letters MUST be converted to Telugu script:
                   - "Tinara" / "Tinnara" -> "తిన్నారా"
                   - "Tinava" / "Tinnava" -> "తిన్నావా"
                   - "Namaskaram" -> "నమస్కారం"
                   - "Bagunnara" -> "బాగున్నారా"
                   - "Ela unnaru" -> "ఎలా ఉన్నారు"
                   - "Emi chestunnav" -> "ఏమి చేస్తున్నావ్"
                3. Hindi words written in English/Latin letters MUST be converted to Devanagari script:
                   - "Namaste" -> "नमस्ते"
                   - "Kaise ho" -> "कैसे हो"
                   - "Khana khaya" -> "खाना खाया"
                   - "Kya kar rahe ho" -> "क्या कर रहे हो"
                   - "Bhai" -> "भाई"

                EXAMPLES:
                - Input: "Hi, good morning, Tinara."
                  Output: "Hi, good morning, తిన్నారా."
                - Input: "Hello bro bagunnara?"
                  Output: "Hello bro బాగున్నారా?"
                - Input: "Good morning namaste kaise ho"
                  Output: "Good morning नमस्ते कैसे हो"

                Return ONLY the converted text. Do NOT add notes or conversational replies.
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

            MediaType jsonMediaType = MediaType.parse("application/json");
            RequestBody body = RequestBody.create(jsonMediaType, jsonPayload);

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
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