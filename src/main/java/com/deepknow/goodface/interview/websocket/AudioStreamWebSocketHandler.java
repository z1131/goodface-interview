package com.deepknow.goodface.interview.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepknow.goodface.interview.stt.MockSttClient;
import com.deepknow.goodface.interview.stt.AliyunSttClient;
import com.deepknow.goodface.interview.stt.SttClient;
import com.deepknow.goodface.interview.llm.LlmClient;
import com.deepknow.goodface.interview.llm.AliyunLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import org.springframework.stereotype.Component;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ServerEndpoint(value = "/audio/stream")
public class AudioStreamWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(AudioStreamWebSocketHandler.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private SttClient sttClient;
    private LlmClient llmClient;

    @OnOpen
    public void onOpen(Session session) {
        log.info("WS connected: {}", session.getId());
        String sessionId = parseQueryParam(session, "sessionId");
        String provider = System.getProperty("stt.provider", "mock");
        String apiKeyEnv = System.getProperty("stt.apiKeyEnv", "DASHSCOPE_API_KEY");
        String apiKeyProp = System.getProperty("stt.apiKey");
        String apiKey = (apiKeyProp != null && !apiKeyProp.isEmpty()) ? apiKeyProp : System.getenv(apiKeyEnv);
        String model = System.getProperty("stt.model", "gummy-realtime-v1");
        int sampleRate = Integer.parseInt(System.getProperty("stt.sampleRate", "16000"));
        String language = System.getProperty("stt.language", "zh-CN");

        if ("aliyun".equalsIgnoreCase(provider)) {
            sttClient = new AliyunSttClient();
        } else {
            sttClient = new MockSttClient();
        }

        sttClient.init(apiKey, model, sampleRate, language);
        // 初始化 LLM 客户端
        String llmProvider = System.getProperty("llm.provider", "aliyun");
        String llmApiKeyEnv = System.getProperty("llm.apiKeyEnv", "DASHSCOPE_API_KEY");
        String llmApiKeyProp = System.getProperty("llm.apiKey");
        String llmApiKey = (llmApiKeyProp != null && !llmApiKeyProp.isEmpty()) ? llmApiKeyProp : System.getenv(llmApiKeyEnv);
        String llmModel = System.getProperty("llm.model", "qwen-turbo");
        double llmTemperature = Double.parseDouble(System.getProperty("llm.temperature", "0.5"));
        double llmTopP = Double.parseDouble(System.getProperty("llm.topP", "0.9"));
        int llmMaxTokens = Integer.parseInt(System.getProperty("llm.maxTokens", "512"));
        boolean llmStreaming = Boolean.parseBoolean(System.getProperty("llm.streaming", "true"));

        if ("aliyun".equalsIgnoreCase(llmProvider)) {
            llmClient = new AliyunLlmClient();
        } else {
            llmClient = new AliyunLlmClient(); // 暂用同实现作为默认
        }
        llmClient.init(llmApiKey, llmModel, llmTemperature, llmTopP, llmMaxTokens, llmStreaming);

        final boolean[] sttFailed = { false };
        sttClient.startSession(sessionId,
                partial -> {
                    try {
                        sendJson(session, Map.of("type", "stt_partial", "content", partial));
                    } catch (IOException e) {
                        log.warn("Failed to send partial", e);
                    }
                },
                fin -> {
                    try {
                        sendJson(session, Map.of("type", "stt_final", "content", fin));
                    } catch (IOException e) {
                        log.warn("Failed to send final", e);
                    }
                    // 在 final 到达后触发问题识别与答案生成（异步）
                    scheduler.execute(() -> {
                        try {
                            String question = llmClient.extractQuestion(fin);
                            if (question != null && !question.isEmpty()) {
                                try { sendJson(session, Map.of("type", "question", "content", question)); }
                                catch (IOException e) { log.warn("send question failed", e); }
                            }
                            String answer = llmClient.generateAnswer(question != null ? question : fin, "");
                            if (answer != null && !answer.isEmpty()) {
                                try { sendJson(session, Map.of("type", "answer", "content", answer)); }
                                catch (IOException e) { log.warn("send answer failed", e); }
                            }
                        } catch (Exception ex) {
                            log.warn("LLM processing failed", ex);
                            try { sendJson(session, Map.of("type", "error", "code", "LLM_ERROR", "message", ex.getMessage())); }
                            catch (IOException ignored) {}
                        }
                    });
                },
                err -> {
                    sttFailed[0] = true;
                    log.warn("STT error: {}", err.getMessage(), err);
                }
        );
        // 向前端发送 STT 就绪信号，提醒可开始推送音频
        scheduler.schedule(() -> {
            try {
                if (!sttFailed[0]) {
                    sendJson(session, Map.of("type", "stt_ready"));
                } else {
                    log.warn("Skip stt_ready due to STT startup failure");
                }
            } catch (IOException e) {
                log.warn("Failed to send stt_ready", e);
            }
        }, 300, TimeUnit.MILLISECONDS);
        // 移除默认开场问题，避免前端一直显示固定文案
    }

    @OnMessage
    public void onBinaryMessage(ByteBuffer message, Session session) {
        log.debug("Received audio chunk, bytes={}", message.remaining());
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        sttClient.sendAudio(bytes);
    }

    @OnMessage
    public void onTextMessage(String text, Session session) {
        log.debug("Received text: {}", text);
        // 控制帧示例：{"type":"control","action":"end"}
        // 可根据需要扩展暂停/恢复等
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.info("WS closed: {} status={}", session.getId(), reason);
        try {
            sttClient.flush();
        } catch (Exception e) {
            log.warn("Flush error", e);
        }
        try {
            sttClient.close();
        } catch (Exception e) {
            log.warn("Close error", e);
        }
        try {
            if (llmClient != null) llmClient.close();
        } catch (Exception e) {
            log.warn("LLM close error", e);
        }
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.warn("WS error: {}", throwable.getMessage(), throwable);
        try {
            sttClient.close();
        } catch (Exception e) {
            log.warn("Close error after onError", e);
        }
    }

    private String parseQueryParam(Session session, String key) {
        try {
            URI uri = session.getRequestURI();
            String query = uri.getQuery();
            if (query == null || query.isEmpty()) return null;
            String[] pairs = query.split("&");
            for (String p : pairs) {
                int i = p.indexOf('=');
                if (i > 0) {
                    String k = p.substring(0, i);
                    if (key.equals(k)) return p.substring(i + 1);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse query param {}", key);
        }
        return null;
    }

    private void sendJson(Session session, Map<String, Object> obj) throws IOException {
        if (session != null && session.isOpen()) {
            String json = objectMapper.writeValueAsString(obj);
            session.getBasicRemote().sendText(json);
        }
    }
}