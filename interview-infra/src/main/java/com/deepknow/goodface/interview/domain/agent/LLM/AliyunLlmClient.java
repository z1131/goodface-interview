package com.deepknow.goodface.interview.domain.agent.LLM;

import com.deepknow.goodface.interview.domain.agent.LlmClient;
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
    private String sessionId;

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    @Override
    public void init(String apiKey, String model, double temperature, double topP, int maxTokens, boolean streaming) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.streaming = streaming;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        log.info("LLM init: model={} temperature={} topP={} maxTokens={} streaming={} sessionId={}",
                model, temperature, topP, maxTokens, streaming, this.sessionId);
    }

    private String preview(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max));
    }

    @Override
    public String extractQuestion(String text, String context) {
        try {
            String system = "你是面试问题识别器，从文本中识别是否包含明确问题，若有则抽取该问题。";
            String user = "文本：" + (text == null ? "" : text) + "\n上下文：" + (context == null ? "" : context) + "\n请返回识别到的问题或返回'无问题'。";
            JsonNode root = call(model, system, user);
            JsonNode output = root.path("output").path("choices");
            if (output.isArray() && output.size() > 0) {
                JsonNode content = output.get(0).path("message").path("content");
                return content.asText("");
            }
            return "无问题";
        } catch (Exception e) {
            log.warn("LLM extractQuestion failed. sessionId=" + this.sessionId, e);
            return "无问题";
        }
    }

    @Override
    public String generateAnswer(String question, String context) {
        try {
            String system = "你是面试候选人助手，基于提供的背景与岗位需求，生成简洁、具体、专业的中文回答，避免虚构事实。";
            String user = "问题：" + (question == null ? "" : question) + "\n上下文：" + (context == null ? "" : context) + "\n请直接给出答案：";
            JsonNode root = call(model, system, user);
            JsonNode output = root.path("output").path("choices");
            if (output.isArray() && output.size() > 0) {
                JsonNode content = output.get(0).path("message").path("content");
                return content.asText("");
            }
            return "";
        } catch (Exception e) {
            log.warn("LLM generateAnswer failed. sessionId=" + this.sessionId, e);
            return "";
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
        log.info("LLM call status: {} model={} streaming=false sessionId={}", resp.statusCode(), model, this.sessionId);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": " + resp.body());
        }
        String bodyStr = resp.body();
        try {
            return mapper.readTree(bodyStr);
        } finally {
            log.debug("LLM call body preview: {} sessionId={}", preview(bodyStr, 200), this.sessionId);
        }
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
            log.info("LLM stream start: model={} qLen={} ctxLen={} temp={} topP={} maxTokens={} sessionId={}",
                    model, question == null ? 0 : question.length(), context == null ? 0 : context.length(),
                    temperature, topP, maxTokens, this.sessionId);
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
            log.info("LLM stream status: {} model={} sessionId={}", resp.statusCode(), model, this.sessionId);
            if (resp.statusCode() / 100 != 2) {
                String errBody = "";
                try (java.io.InputStream es = resp.body()) {
                    errBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignore) {}
                throw new RuntimeException("LLM stream HTTP " + resp.statusCode() + " body=" + preview(errBody, 200));
            }

            int chunkCount = 0;
            int totalChars = 0;

            try (java.io.InputStream is = resp.body()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (!data.isEmpty() && !"[DONE]".equalsIgnoreCase(data)) {
                            try {
                                JsonNode node = mapper.readTree(data);
                                JsonNode choices = node.path("output").path("choices");
                                if (choices.isArray() && choices.size() > 0) {
                                    JsonNode msg = choices.get(0).path("message").path("content");
                                    String delta = msg.asText("");
                                    if (delta != null && !delta.isEmpty()) {
                                        chunkCount++;
                                        totalChars += delta.length();
                                        if (onDelta != null) onDelta.accept(delta);
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("LLM stream parse failed. sessionId=" + this.sessionId, e);
                            }
                        }
                    }
                }
            }

            log.info("LLM stream end: chunks={} totalChars={} sessionId={}", chunkCount, totalChars, this.sessionId);
            if (onComplete != null) onComplete.run();
        } catch (Exception e) {
            log.warn("LLM stream failed. sessionId=" + this.sessionId, e);
            if (onError != null) onError.accept(e);
        }
    }

    @Override
    public com.deepknow.goodface.interview.domain.agent.EquivalenceResult judgeQuestionEquivalence(String lastQuestion, String candidate, String context) {
        try {
            String system = "你是面试问题去重器与规范化器。任务：根据‘最近问题’‘当前输入’‘上下文’判断是否为同题或只是补充，不要产生新问题。仅当确认为新问题时返回 NEW；否则返回 SAME 或 ELABORATION；若不存在问题返回 NONE。请严格输出 JSON。";
            String user = "最近问题：" + safe(lastQuestion) +
                    "\n当前输入：" + safe(candidate) +
                    "\n上下文：" + safe(context) +
                    "\n请返回 JSON：{\"class\": \"SAME|ELABORATION|NEW|NONE\", \"canonical\": \"...\", \"reason\": \"...\"}";
            JsonNode root = call(model, system, user);
            JsonNode output = root.path("output").path("choices");
            String content = null;
            if (output.isArray() && output.size() > 0) {
                content = output.get(0).path("message").path("content").asText("");
            }
            if (content == null || content.isEmpty()) {
                log.warn("LLM judgeQuestionEquivalence empty content. sessionId={}", this.sessionId);
                return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("SAME", safe(candidate), "empty content");
            }
            JsonNode json;
            try { json = mapper.readTree(content); } catch (Exception e) {
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String sub = content.substring(start, end + 1);
                    try { json = mapper.readTree(sub); } catch (Exception e2) {
                        log.warn("LLM judgeQuestionEquivalence parse failed. content preview={} sessionId={}", preview(content, 120), this.sessionId);
                        return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("SAME", safe(candidate), "parse failed");
                    }
                } else {
                    log.warn("LLM judgeQuestionEquivalence no json found. content preview={} sessionId={}", preview(content, 120), this.sessionId);
                    return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("SAME", safe(candidate), "no json");
                }
            }
            String clazz = json.path("class").asText("SAME");
            String canonical = json.path("canonical").asText(safe(candidate));
            String reason = json.path("reason").asText("");
            String cUpper = clazz == null ? "SAME" : clazz.trim().toUpperCase();
            if (!("NEW".equals(cUpper) || "SAME".equals(cUpper) || "ELABORATION".equals(cUpper) || "NONE".equals(cUpper))) {
                cUpper = "SAME";
            }
            com.deepknow.goodface.interview.domain.agent.EquivalenceResult res = new com.deepknow.goodface.interview.domain.agent.EquivalenceResult(cUpper, canonical, reason);
            log.info("LLM judgeQuestionEquivalence: {} sessionId={}", res, this.sessionId);
            return res;
        } catch (Exception e) {
            log.warn("LLM judgeQuestionEquivalence failed. sessionId=" + this.sessionId, e);
            return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("SAME", safe(candidate), "exception");
        }
    }

    @Override
    public com.deepknow.goodface.interview.domain.agent.EquivalenceResult judgeSegmentRelation(String lastQuestion, String segment, String context) {
        try {
            String system = "你是段落关系判定器。当没有明确问题时，判断该段落是否是为最近问题的补充。仅在补充时返回 ELABORATION，否则返回 NONE。严格输出 JSON。";
            String user = "最近问题：" + safe(lastQuestion) +
                    "\n当前段落：" + safe(segment) +
                    "\n上下文：" + safe(context) +
                    "\n请返回 JSON：{\"class\": \"ELABORATION|NONE\", \"reason\": \"...\"}";
            JsonNode root = call(model, system, user);
            JsonNode output = root.path("output").path("choices");
            String content = null;
            if (output.isArray() && output.size() > 0) {
                content = output.get(0).path("message").path("content").asText("");
            }
            if (content == null || content.isEmpty()) {
                log.warn("LLM judgeSegmentRelation empty content. sessionId={}", this.sessionId);
                return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("NONE", "", "empty content");
            }
            JsonNode json;
            try { json = mapper.readTree(content); } catch (Exception e) {
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String sub = content.substring(start, end + 1);
                    try { json = mapper.readTree(sub); } catch (Exception e2) {
                        log.warn("LLM judgeSegmentRelation parse failed. content preview={} sessionId={}", preview(content, 120), this.sessionId);
                        return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("NONE", "", "parse failed");
                    }
                } else {
                    log.warn("LLM judgeSegmentRelation no json found. content preview={} sessionId={}", preview(content, 120), this.sessionId);
                    return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("NONE", "", "no json");
                }
            }
            String clazz = json.path("class").asText("NONE");
            String reason = json.path("reason").asText("");
            String cUpper = clazz == null ? "NONE" : clazz.trim().toUpperCase();
            if (!("ELABORATION".equals(cUpper) || "NONE".equals(cUpper))) {
                cUpper = "NONE";
            }
            com.deepknow.goodface.interview.domain.agent.EquivalenceResult res = new com.deepknow.goodface.interview.domain.agent.EquivalenceResult(cUpper, "", reason);
            log.info("LLM judgeSegmentRelation: {} sessionId={}", res, this.sessionId);
            return res;
        } catch (Exception e) {
            log.warn("LLM judgeSegmentRelation failed. sessionId=" + this.sessionId, e);
            return new com.deepknow.goodface.interview.domain.agent.EquivalenceResult("NONE", "", "exception");
        }
    }

    @Override
    public com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult updateContextMemory(String currentQuestion, String accumulatedContext, String recentContext) {
        try {
            String system = "你是对话摘要与事实抽取器。基于当前问题与已有上下文，生成简洁的滚动摘要（中文，3-5 条要点），并抽取关键事实为键值对。严格输出 JSON。";
            String user = "当前问题：" + safe(currentQuestion) +
                    "\n已累积上下文：" + safe(accumulatedContext) +
                    "\n最近上下文：" + safe(recentContext) +
                    "\n请返回 JSON：{\"summary\": \"...\", \"facts\": {\"key\": \"value\"}}";
            JsonNode root = call(model, system, user);
            JsonNode output = root.path("output").path("choices");
            String content = null;
            if (output.isArray() && output.size() > 0) {
                content = output.get(0).path("message").path("content").asText("");
            }
            if (content == null || content.isEmpty()) {
                log.warn("LLM updateContextMemory empty content. sessionId={}", this.sessionId);
                return new com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult("", java.util.Collections.emptyMap());
            }
            JsonNode json;
            try { json = mapper.readTree(content); } catch (Exception e) {
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    String sub = content.substring(start, end + 1);
                    try { json = mapper.readTree(sub); } catch (Exception e2) {
                        log.warn("LLM updateContextMemory parse failed. content preview={} sessionId={}", preview(content, 120), this.sessionId);
                        return new com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult("", java.util.Collections.emptyMap());
                    }
                } else {
                    log.warn("LLM updateContextMemory no json found. content preview={} sessionId={}", preview(content, 120), this.sessionId);
                    return new com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult("", java.util.Collections.emptyMap());
                }
            }
            String summary = json.path("summary").asText("");
            java.util.Map<String, String> facts = new java.util.HashMap<>();
            JsonNode factsNode = json.path("facts");
            if (factsNode.isObject()) {
                factsNode.fields().forEachRemaining(e -> facts.put(e.getKey(), e.getValue().asText("")));
            }
            com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult res = new com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult(summary, facts);
            log.info("LLM updateContextMemory: {} sessionId={}", res, this.sessionId);
            return res;
        } catch (Exception e) {
            log.warn("LLM updateContextMemory failed. sessionId=" + this.sessionId, e);
            return new com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult("", java.util.Collections.emptyMap());
        }
    }
}