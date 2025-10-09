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
    }

    @Override
    public String extractQuestion(String text) {
        try {
            String system = "你是文本抽取器，只从输入中抽取最可能的提问句。若没有问题则返回空。输出仅包含问题文本，无多余说明。";
            String prompt = "输入：" + text + "\n输出：";
            JsonNode resp = call(model, system, prompt);
            return pickText(resp);
        } catch (Exception e) {
            log.warn("extractQuestion failed", e);
            return null;
        }
    }

    @Override
    public String generateAnswer(String question, String context) {
        try {
            String system = "你是面试候选人助手，基于提供的背景与岗位需求，生成简洁、具体、专业的中文回答，避免虚构事实。";
            String prompt = "问题：" + safe(question) + "\n上下文：" + safe(context) + "\n请直接给出答案：";
            JsonNode resp = call(model, system, prompt);
            return pickText(resp);
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
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    private String pickText(JsonNode resp) {
        if (resp == null) return null;
        // 兼容多种返回结构：output_text 或 choices[0].message.content
        String t = null;
        JsonNode out = resp.path("output_text");
        if (out.isTextual()) t = out.asText();
        if (t == null || t.isEmpty()) {
            JsonNode choices = resp.path("choices");
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
}