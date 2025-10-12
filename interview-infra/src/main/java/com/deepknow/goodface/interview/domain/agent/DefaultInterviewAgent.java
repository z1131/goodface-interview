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
    private String lastQuestion;
    private java.util.ArrayDeque<String> recentQuestions;
    private int contextWindowSize = 3;
    private String userPrompt;

    public DefaultInterviewAgent(AgentFactory factory) {
        this.factory = factory;
    }

    @Override
    public void start(com.deepknow.goodface.interview.domain.session.model.SessionContext ctx,
                      Consumer<String> onSttPartial,
                      Consumer<String> onSttFinal,
                      Consumer<String> onQuestion,
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

        // 初始化上下文窗口（最近几轮问题）
        this.contextWindowSize = getInt(cfg, "context.windowSize", 3);
        if (this.contextWindowSize < 1) this.contextWindowSize = 1;
        this.recentQuestions = new java.util.ArrayDeque<>(this.contextWindowSize);
        // 读取用户填写的提示词（来自 Portal 创建会话时的配置）
        this.userPrompt = getString(cfg, "prompt", "");

        final boolean[] sttFailed = { false };
        sttClient.startSession(ctx.getSessionId(),
                partial -> { if (onSttPartial != null) onSttPartial.accept(partial); },
                fin -> {
                    if (onSttFinal != null) onSttFinal.accept(fin);
                    // 触发 LLM 处理
                    scheduler.execute(() -> {
                        try {
                            String question = llmClient.extractQuestion(fin, buildContextString());
                            // 将识别出的新问题推送到上层，避免重复
                            String normQ = normalize(question);
                            String normLast = normalize(lastQuestion);
                            // 过滤无问题信号
                            boolean isNoQuestion = question != null && "无问题".equals(question.trim());
                            if (!isNoQuestion && normQ != null && !normQ.isEmpty() && !normQ.equals(normLast)) {
                                lastQuestion = question;
                                // 维护最近问题窗口
                                addRecentQuestion(normQ);
                                if (onQuestion != null) onQuestion.accept(question);
                            }
                            if (llmStreamingEnabled) {
                                llmClient.generateAnswerStream(
                                        question != null ? question : fin,
                                        buildContextString(),
                                        delta -> { if (onAnswerDelta != null) onAnswerDelta.accept(delta); },
                                        () -> { if (onAnswerComplete != null) onAnswerComplete.run(); },
                                        ex -> { if (onError != null) onError.accept(ex); }
                                );
                            } else {
                                String answer = llmClient.generateAnswer(question != null ? question : fin, buildContextString());
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

    private void addRecentQuestion(String q) {
        if (q == null || q.isEmpty()) return;
        // 避免重复相邻问题
        String last = recentQuestions.peekLast();
        if (last != null && last.equals(q)) return;
        if (recentQuestions.size() >= contextWindowSize) {
            recentQuestions.pollFirst();
        }
        recentQuestions.offerLast(q);
    }

    private String buildContextString() {
        if (recentQuestions == null || recentQuestions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(256);
        if (userPrompt != null && !userPrompt.isEmpty()) {
            sb.append("用户提示词：").append(userPrompt).append("\n");
        }
        sb.append("最近问题：");
        boolean first = true;
        for (String q : recentQuestions) {
            if (!first) sb.append(" | ");
            sb.append(q);
            first = false;
        }
        return sb.toString();
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

    private String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        // 简单去噪：去除中文/英文标点与多空格
        t = t.replaceAll("[\n\r]", " ");
        t = t.replaceAll("[，。！？、；：:,.!?]", "");
        t = t.replaceAll("\\s+", " ");
        return t.toLowerCase();
    }
}