package com.deepknow.goodface.interview.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 阿里云百炼文本模型客户端（REST）。
 * 使用 DashScope 文本生成接口进行问题识别与答案生成。
 */
public class AliyunLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(AliyunLlmClient.class);
    private static final String GEN_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
    // 阿里云 OpenAI 兼容接口（流式）
    private static final String CHAT_COMPAT_URL = "https://dashscope.aliyuncs.com/compatible/v1/chat/completions";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient httpClient;
    private String apiKey;
    private String model;
    private double temperature;
    private double topP;
    private int maxTokens;
    private boolean streaming;

    @Override
    public void init(String apiKey, String model, double temperature, double topP, int maxTokens, boolean streaming) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.streaming = streaming;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        log.info("LLM init: model={}, temperature={}, topP={}, maxTokens={}, streaming={}",
                model, temperature, topP, maxTokens, streaming);
    }

    @Override
    public String extractQuestion(String text) {
        try {
            log.info("LLM extractQuestion start: textLen={}", text == null ? 0 : text.length());
            String system = "你是一个面试问题提取器，输入的文本为实时录音转文字识别出来的文本内容，识别内容为面试对话并且可能会有偏差，请你识别出其中面试官提出的问题。";
            String prompt = "输入：" + text + "\n输出：";
            JsonNode resp = call(model, system, prompt);
            String result = pickText(resp);
            log.info("LLM extractQuestion done: resultLen={} preview=\"{}\"",
                    result == null ? 0 : result.length(), preview(result, 80));
            log.debug("LLM extractQuestion content: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("extractQuestion failed", e);
            return null;
        }
    }

    @Override
    public String generateAnswer(String question, String context) {
        try {
            log.info("LLM generateAnswer start: qLen={} ctxLen={}",
                    question == null ? 0 : question.length(), context == null ? 0 : context.length());
            String system = "你是面试候选人助手，基于提供的背景与岗位需求，生成简洁、具体、专业的中文回答，避免虚构事实。";
            String prompt = "问题：" + safe(question) + "\n上下文：" + safe(context) + "\n请直接给出答案：";
            JsonNode resp = call(model, system, prompt);
            String result = pickText(resp);
            log.info("LLM generateAnswer done: resultLen={} preview=\"{}\"",
                    result == null ? 0 : result.length(), preview(result, 120));
            log.debug("LLM generateAnswer content: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("generateAnswer failed", e);
            return null;
        }
    }

    private JsonNode call(String model, String system, String user) throws Exception {
        Map<String, Object> root = new HashMap<>();
        root.put("model", model);
        Map<String, Object> params = new HashMap<>();
        params.put("temperature", temperature);
        params.put("top_p", topP);
        params.put("max_tokens", maxTokens);
        params.put("enable_streaming", false); // 统一走非流式，服务端再分片推送
        // 统一返回为消息格式，便于稳定解析
        params.put("result_format", "message");
        root.put("parameters", params);

        // messages 风格
        Map<String, Object> mSystem = new HashMap<>();
        mSystem.put("role", "system");
        mSystem.put("content", system);
        Map<String, Object> mUser = new HashMap<>();
        mUser.put("role", "user");
        mUser.put("content", user);
        root.put("input", Map.of("messages", new Object[]{mSystem, mUser}));

        String body = mapper.writeValueAsString(root);
        HttpRequest req = HttpRequest.newBuilder(URI.create(GEN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log.info("LLM call status: {} model={} streaming=false", resp.statusCode(), model);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        String bodyStr = resp.body();
        try {
            return mapper.readTree(bodyStr);
        } finally {
            log.debug("LLM call body preview: {}", preview(bodyStr, 200));
        }
    }

    private String pickText(JsonNode resp) {
        if (resp == null) return null;
        // 兼容多种返回结构：output_text 或 choices[0].message.content
        // 同时兼容 DashScope 原生结构：output.text 或 output.choices[0].message.content
        String t = null;
        // 顶层扁平字段
        JsonNode outTextFlat = resp.path("output_text");
        if (outTextFlat.isTextual()) t = outTextFlat.asText();
        // 顶层 choices.message.content
        if (isNullOrEmpty(t)) {
            JsonNode choicesTop = resp.path("choices");
            if (choicesTop.isArray() && choicesTop.size() > 0) {
                JsonNode msg = choicesTop.get(0).path("message");
                t = msg.path("content").asText(null);
            }
        }
        // output.text
        if (isNullOrEmpty(t)) {
            JsonNode output = resp.path("output");
            JsonNode text = output.path("text");
            if (text.isTextual()) t = text.asText();
        }
        // output.choices[0].message.content
        if (isNullOrEmpty(t)) {
            JsonNode output = resp.path("output");
            JsonNode choices = output.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).path("message");
                t = msg.path("content").asText(null);
            }
        }
        return t;
    }

    private String safe(String s) { return s == null ? "" : s; }

    @Override
    public void close() {}

    @Override
    public void generateAnswerStream(String question, String context,
                                     java.util.function.Consumer<String> onDelta,
                                     Runnable onComplete,
                                     java.util.function.Consumer<Throwable> onError) {
        try {
            log.info("LLM stream start: model={} qLen={} ctxLen={} temp={} topP={} maxTokens={}",
                    model, question == null ? 0 : question.length(), context == null ? 0 : context.length(),
                    temperature, topP, maxTokens);
            String system = "你是面试候选人助手，基于提供的背景与岗位需求，生成简洁、具体、专业的中文回答，避免虚构事实。";
            String user = "问题：" + safe(question) + "\n上下文：" + safe(context) + "\n请直接给出答案：";

            // DashScope 原生文本生成接口（流式）
            Map<String, Object> root = new HashMap<>();
            root.put("model", model);
            Map<String, Object> params = new HashMap<>();
            params.put("temperature", temperature);
            params.put("top_p", topP);
            params.put("max_tokens", maxTokens);
            params.put("enable_streaming", true);
            // 某些模型支持增量输出标志
            params.put("incremental_output", true);
            params.put("result_format", "message");
            root.put("parameters", params);

            Map<String, Object> mSystem = new HashMap<>();
            mSystem.put("role", "system");
            mSystem.put("content", system);
            Map<String, Object> mUser = new HashMap<>();
            mUser.put("role", "user");
            mUser.put("content", user);
            root.put("input", Map.of("messages", new Object[]{mSystem, mUser}));

            String body = mapper.writeValueAsString(root);
            HttpRequest req = HttpRequest.newBuilder(URI.create(GEN_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            log.info("LLM stream status: {} model={}", resp.statusCode(), model);
            if (resp.statusCode() / 100 != 2) {
                String errBody = "";
                try (java.io.InputStream es = resp.body()) {
                    errBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignore) {}
                throw new RuntimeException("LLM stream HTTP " + resp.statusCode() + " body=" + preview(errBody, 200));
            }

            int chunkCount = 0;
            int totalChars = 0;
            try (java.io.InputStream is = resp.body();
                 java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line == null) break;
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equalsIgnoreCase(data)) {
                        log.info("LLM stream complete: chunks={} totalChars={}", chunkCount, totalChars);
                        if (onComplete != null) onComplete.run();
                        break;
                    }
                    try {
                        JsonNode node = mapper.readTree(data);
                        // 兼容不同结构：优先增量文本字段 / 退化到 output.text / output.choices[].message.content / 顶层扁平
                        String deltaText = null;
                        // 顶层增量/完整文本（有些模型会扁平化）
                        if (node.path("output_delta_text").isTextual()) {
                            deltaText = node.path("output_delta_text").asText();
                        }
                        if (isNullOrEmpty(deltaText) && node.path("output_text").isTextual()) {
                            deltaText = node.path("output_text").asText();
                        }
                        // output.text
                        if (isNullOrEmpty(deltaText)) {
                            JsonNode out = node.path("output");
                            if (out.path("text").isTextual()) {
                                deltaText = out.path("text").asText();
                            }
                        }
                        // output.choices[0].message.content
                        if (isNullOrEmpty(deltaText)) {
                            JsonNode choices = node.path("output").path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode msg = choices.get(0).path("message");
                                deltaText = msg.path("content").asText(null);
                            }
                        }
                        // 顶层 choices[0].message.content（兼容）
                        if (isNullOrEmpty(deltaText)) {
                            JsonNode choices = node.path("choices");
                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode msg = choices.get(0).path("message");
                                deltaText = msg.path("content").asText(null);
                            }
                        }
                        if (deltaText != null && !deltaText.isEmpty()) {
                            chunkCount++;
                            totalChars += deltaText.length();
                            log.info("LLM stream delta: len={} preview=\"{}\"", deltaText.length(), preview(deltaText, 80));
                            log.debug("LLM stream delta content: {}", deltaText);
                            if (onDelta != null) onDelta.accept(deltaText);
                        }
                    } catch (Exception parseEx) {
                        log.warn("parse stream chunk failed: {}", parseEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("LLM stream error: {}", e.getMessage());
            if (onError != null) onError.accept(e);
        }
    }

    private String preview(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\n", " ");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private boolean isNullOrEmpty(String s) { return s == null || s.isEmpty(); }
}