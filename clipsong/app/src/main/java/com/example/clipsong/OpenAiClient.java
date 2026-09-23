package com.example.clipsong;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OpenAiClient {
    private OpenAiClient() {}

    static String transcribe(File wav, String apiKey) throws Exception {
        requireKey(apiKey);
        String boundary = "----ClipSong" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection)new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = c.getOutputStream()) {
            writeField(out, boundary, "model", "gpt-4o-mini-transcribe");
            writeField(out, boundary, "response_format", "json");
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + wav.getName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(wav)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        String body = readResponse(c);
        JSONObject json = new JSONObject(body);
        String text = json.optString("text", "").trim();
        if (text.isEmpty()) throw new IOException("Сервис не вернул распознанный текст");
        return text;
    }

    static String literaryEdit(String source, String instruction, String apiKey) throws Exception {
        requireKey(apiKey);
        if (source == null || source.trim().isEmpty()) throw new IOException("Сначала распознайте текст");

        String userInstruction = instruction == null || instruction.trim().isEmpty()
                ? "Литературно отредактируй текст: убери повторы и слова-паразиты, сохрани факты, смысл и голос автора. Не добавляй событий, которых нет в исходнике."
                : instruction.trim();

        JSONObject req = new JSONObject();
        req.put("model", "gpt-4o-mini");
        req.put("temperature", 0.5);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", "Ты литературный редактор. Возвращай только отредактированный текст без комментариев и пояснений."));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userInstruction + "\n\nИсходный текст:\n" + source));
        req.put("messages", messages);

        HttpURLConnection c = (HttpURLConnection)new URL("https://api.openai.com/v1/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        try (OutputStream out = c.getOutputStream()) {
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        String body = readResponse(c);
        JSONObject json = new JSONObject(body);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("Сервис не вернул редактуру");
        String result = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
        if (result.isEmpty()) throw new IOException("Получен пустой результат");
        return result;
    }

    private static void requireKey(String key) throws IOException {
        if (key == null || key.trim().isEmpty()) {
            throw new IOException("Для этой функции нужен OpenAI API key");
        }
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readResponse(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder s = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) s.append(line);
            body = s.toString();
        }
        if (code < 200 || code >= 300) {
            String message = body;
            try {
                JSONObject j = new JSONObject(body);
                message = j.optJSONObject("error") != null ? j.optJSONObject("error").optString("message", body) : body;
            } catch (Exception ignored) {}
            throw new IOException("AI API: " + message);
        }
        return body;
    }
}
