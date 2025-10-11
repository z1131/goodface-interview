package com.deepknow.goodface.interview.domain.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 默认 Agent 实现：接收 STT 最终文本，触发 LLM 问题提取与答案生成（支持流式）。
 */
public class DefaultInterviewAgent implements InterviewAgent {
    private static final Logger log = LoggerFactory.getLogger(DefaultInterviewAgent.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final AgentFactory factory;
    private SttClient sttClient;
    private LlmClient llmClient;
    private boolean llmStreamingEnabled = true;

    public DefaultInterviewAgent(AgentFactory factory) {
        this.factory = factory;
    }

    @Override
    public void start(com.deepknow.goodface.interview.domain.session.model.SessionContext ctx,
                      Consumer<String> onSttPartial,
                      Consumer<String> onSttFinal,
                      Consumer<String> onAnswerDelta,
                      Runnable onAnswerComplete,
                      Consumer<Throwable> onError,
                      Runnable onSttReady) {
        // 读取 LLM/STT 配置并初始化
        sttClient = factory.createStt();
        java.util.Map<String, Object> cfg = ctx.getConfig();
        String apiKeyEnv = getString(cfg, "stt.apiKeyEnv", System.getProperty("stt.apiKeyEnv", "DASHSCOPE_API_KEY"));
        String apiKeyProp = getString(cfg, "stt.apiKey", System.getProperty("stt.apiKey"));
        String apiKey = (apiKeyProp != null && !apiKeyProp.isEmpty()) ? apiKeyProp : System.getenv(apiKeyEnv);
        String model = getString(cfg, "stt.model", System.getProperty("stt.model", "gummy-realtime-v1"));
        int sampleRate = getInt(cfg, "stt.sampleRate", Integer.parseInt(System.getProperty("stt.sampleRate", "16000")));
        String language = getString(cfg, "stt.language", System.getProperty("stt.language", "zh-CN"));
        sttClient.init(apiKey, model, sampleRate, language);

        llmClient = factory.createLlm();
        String llmApiKeyEnv = getString(cfg, "llm.apiKeyEnv", System.getProperty("llm.apiKeyEnv", "DASHSCOPE_API_KEY"));
        String llmApiKeyProp = getString(cfg, "llm.apiKey", System.getProperty("llm.apiKey"));
        String llmApiKey = (llmApiKeyProp != null && !llmApiKeyProp.isEmpty()) ? llmApiKeyProp : System.getenv(llmApiKeyEnv);
        String llmModel = getString(cfg, "llm.model", System.getProperty("llm.model", "qwen-turbo"));
        double llmTemperature = getDouble(cfg, "llm.temperature", Double.parseDouble(System.getProperty("llm.temperature", "0.5")));
        double llmTopP = getDouble(cfg, "llm.topP", Double.parseDouble(System.getProperty("llm.topP", "0.9")));
        int llmMaxTokens = getInt(cfg, "llm.maxTokens", Integer.parseInt(System.getProperty("llm.maxTokens", "512")));
        boolean llmStreaming = getBoolean(cfg, "llm.streaming", Boolean.parseBoolean(System.getProperty("llm.streaming", "true")));
        llmClient.init(llmApiKey, llmModel, llmTemperature, llmTopP, llmMaxTokens, llmStreaming);
        this.llmStreamingEnabled = llmStreaming;

        final boolean[] sttFailed = { false };
        sttClient.startSession(ctx.getSessionId(),
                partial -> { if (onSttPartial != null) onSttPartial.accept(partial); },
                fin -> {
                    if (onSttFinal != null) onSttFinal.accept(fin);
                    // 触发 LLM 处理
                    scheduler.execute(() -> {
                        try {
                            String question = llmClient.extractQuestion(fin);
                            if (llmStreamingEnabled) {
                                llmClient.generateAnswerStream(
                                        question != null ? question : fin,
                                        "",
                                        delta -> { if (onAnswerDelta != null) onAnswerDelta.accept(delta); },
                                        () -> { if (onAnswerComplete != null) onAnswerComplete.run(); },
                                        ex -> { if (onError != null) onError.accept(ex); }
                                );
                            } else {
                                String answer = llmClient.generateAnswer(question != null ? question : fin, "");
                                if (onAnswerDelta != null && answer != null) onAnswerDelta.accept(answer);
                                if (onAnswerComplete != null) onAnswerComplete.run();
                            }
                        } catch (Exception ex) {
                            log.warn("LLM processing failed", ex);
                            if (onError != null) onError.accept(ex);
                        }
                    });
                },
                err -> {
                    sttFailed[0] = true;
                    if (onError != null) onError.accept(err);
                }
        );

        // 通知 STT 就绪（简单延时方案）
        scheduler.schedule(() -> {
            try {
                if (!sttFailed[0]) {
                    if (onSttReady != null) onSttReady.run();
                }
            } catch (Exception e) {
                log.warn("Failed to emit stt_ready", e);
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    @Override
    public void sendAudio(byte[] pcmChunk) {
        if (sttClient != null) sttClient.sendAudio(pcmChunk);
    }

    @Override
    public void flush() {
        try { if (sttClient != null) sttClient.flush(); }
        catch (Exception e) { log.warn("Agent flush error", e); }
    }

    @Override
    public void close() {
        try { if (sttClient != null) sttClient.close(); }
        catch (Exception e) { log.warn("Agent STT close error", e); }
        try { if (llmClient != null) llmClient.close(); }
        catch (Exception e) { log.warn("Agent LLM close error", e); }
    }

    private String getString(java.util.Map<String, Object> cfg, String key, String def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int getInt(java.util.Map<String, Object> cfg, String key, int def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private double getDouble(java.util.Map<String, Object> cfg, String key, double def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v == null) return def;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private boolean getBoolean(java.util.Map<String, Object> cfg, String key, boolean def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v == null) return def;
        try { return Boolean.parseBoolean(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}