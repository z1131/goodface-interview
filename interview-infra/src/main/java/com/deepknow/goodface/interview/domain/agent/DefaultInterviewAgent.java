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
    private java.util.ArrayDeque<String> recentUtterances;
    private int maxUtterances = 5;
    private int debounceMillis = 1200;
    private double similarityThreshold = 0.85;
    private final StringBuilder pendingBuffer = new StringBuilder(512);
    private java.util.concurrent.ScheduledFuture<?> pendingTask;
    private boolean answerOnlyOnQuestion = true;
    // 软端点与 partial 段提交
    private int softEndpointMillis = 1500;
    private int maxSegmentChars = 240;
    private boolean earlyCommitPunctuation = true;
    private final StringBuilder partialBuffer = new StringBuilder(512);
    private java.util.concurrent.ScheduledFuture<?> softEndpointTask;
    private final java.util.ArrayDeque<String> recentCommittedNorm = new java.util.ArrayDeque<>(3);

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
        // 初始化最近陈述与聚合配置
        this.maxUtterances = getInt(cfg, "context.maxUtterances", 5);
        if (this.maxUtterances < 1) this.maxUtterances = 1;
        this.recentUtterances = new java.util.ArrayDeque<>(this.maxUtterances);
        this.debounceMillis = getInt(cfg, "context.debounceMillis", 1200);
        if (this.debounceMillis < 0) this.debounceMillis = 0;
        this.similarityThreshold = getDouble(cfg, "context.similarityThreshold", 0.85);
        this.answerOnlyOnQuestion = getBoolean(cfg, "context.answerOnlyOnQuestion", true);
        // 读取软端点与早提交配置
        this.softEndpointMillis = getInt(cfg, "stt.softEndpointMillis", 1500);
        if (this.softEndpointMillis < 300) this.softEndpointMillis = 300;
        this.maxSegmentChars = getInt(cfg, "stt.maxSegmentChars", 240);
        if (this.maxSegmentChars < 50) this.maxSegmentChars = 50;
        this.earlyCommitPunctuation = getBoolean(cfg, "stt.earlyCommitPunctuation", true);

        final boolean[] sttFailed = { false };
        sttClient.startSession(ctx.getSessionId(),
                partial -> {
                    // 透传到上层（实时字幕等）
                    if (onSttPartial != null) onSttPartial.accept(partial);
                    // 累积 partial 文本
                    appendPartial(partial);
                    // 标点早提交或最大段长限制
                    if (shouldEarlyCommit(partialBuffer)) {
                        String segment = drainPartialBuffer();
                        if (!segment.isEmpty()) {
                            // 取消软端点任务，避免重复
                            cancelSoftEndpoint();
                            scheduler.execute(() -> processSegment(segment, onQuestion, onAnswerDelta, onAnswerComplete, onError));
                        }
                    } else {
                        // 重置软端点定时任务
                        rescheduleSoftEndpoint(onQuestion, onAnswerDelta, onError, onAnswerComplete);
                    }
                },
                fin -> {
                    if (onSttFinal != null) onSttFinal.accept(fin);
                    // 句末事件到达：取消软端点并清空 partial 缓冲，避免重复
                    cancelSoftEndpoint();
                    clearPartialBuffer();
                    // 记录最近陈述（原文）
                    addRecentUtterance(fin);
                    // 聚合：重置去抖定时器，将片段加入待处理缓冲
                    synchronized (pendingBuffer) {
                        if (pendingTask != null) {
                            try { pendingTask.cancel(false); } catch (Exception ignore) {}
                            pendingTask = null;
                        }
                        if (pendingBuffer.length() > 0) pendingBuffer.append(' ');
                        pendingBuffer.append(fin);
                        pendingTask = scheduler.schedule(() -> {
                            processPending(onQuestion, onAnswerDelta, onAnswerComplete, onError);
                        }, debounceMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                },
                err -> {
                    sttFailed[0] = true;
                    if (onError != null) onError.accept(err);
                },
                () -> { if (onSttReady != null) onSttReady.run(); }
        );

        // 去除简单延时方案，改由 STT 客户端在连接建立后触发 onReady
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
        if (recentUtterances != null && !recentUtterances.isEmpty()) {
            sb.append("\n最近陈述：");
            first = true;
            for (String u : recentUtterances) {
                if (!first) sb.append(" | ");
                sb.append(u);
                first = false;
            }
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

    private void addRecentUtterance(String u) {
        if (u == null || u.isEmpty()) return;
        if (recentUtterances.size() >= maxUtterances) {
            recentUtterances.pollFirst();
        }
        recentUtterances.offerLast(u);
    }

    private double jaccardSimilarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na == null || nb == null || na.isEmpty() || nb.isEmpty()) return 0.0;
        java.util.Set<String> sa = new java.util.HashSet<>(java.util.Arrays.asList(na.split(" ")));
        java.util.Set<String> sb = new java.util.HashSet<>(java.util.Arrays.asList(nb.split(" ")));
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        if (union.isEmpty()) return 0.0;
        return (double) inter.size() / (double) union.size();
    }

    private void processPending(Consumer<String> onQuestion,
                                Consumer<String> onAnswerDelta,
                                Runnable onAnswerComplete,
                                Consumer<Throwable> onError) {
        String combined;
        synchronized (pendingBuffer) {
            combined = pendingBuffer.toString().trim();
            pendingBuffer.setLength(0);
            pendingTask = null;
        }
        if (combined.isEmpty()) return;
        // 与最近已提交段做相似度判断，避免重复处理
        String normCombined = normalize(combined);
        if (isSimilarToRecentCommitted(normCombined)) {
            log.debug("Skip pending combined due to similarity with recent committed");
            return;
        }
        try {
            String ctxStr = buildContextString();
            String question = llmClient.extractQuestion(combined, ctxStr);
            String normQ = normalize(question);
            String normLast = normalize(lastQuestion);
            boolean isNoQuestion = question != null && "无问题".equals(question.trim());
            boolean isNewEnough = normQ != null && !normQ.isEmpty() && !normQ.equals(normLast) && jaccardSimilarity(question, lastQuestion) < similarityThreshold;
            if (!isNoQuestion && isNewEnough) {
                lastQuestion = question;
                addRecentQuestion(normQ);
                if (onQuestion != null) onQuestion.accept(question);
            }
            // 若开启“仅在问题时回答”，则在非新问题情况下跳过回答生成
            if (answerOnlyOnQuestion && (isNoQuestion || !isNewEnough)) {
                return;
            }

            String inputForAnswer = (question != null && !isNoQuestion) ? question : combined;
            if (llmStreamingEnabled) {
                llmClient.generateAnswerStream(
                        inputForAnswer,
                        ctxStr,
                        delta -> { if (onAnswerDelta != null) onAnswerDelta.accept(delta); },
                        () -> { if (onAnswerComplete != null) onAnswerComplete.run(); },
                        ex -> { if (onError != null) onError.accept(ex); }
                );
            } else {
                String answer = llmClient.generateAnswer(inputForAnswer, ctxStr);
                if (onAnswerDelta != null && answer != null) onAnswerDelta.accept(answer);
                if (onAnswerComplete != null) onAnswerComplete.run();
            }
            rememberCommitted(normCombined);
        } catch (Exception ex) {
            log.warn("LLM processing failed", ex);
            if (onError != null) onError.accept(ex);
        }
    }

    // ====== 软端点与 partial 处理辅助 ======

    private void appendPartial(String partial) {
        if (partial == null || partial.isEmpty()) return;
        if (partialBuffer.length() > 0) partialBuffer.append(' ');
        partialBuffer.append(partial.trim());
    }

    private boolean shouldEarlyCommit(StringBuilder buf) {
        if (buf == null) return false;
        if (buf.length() >= maxSegmentChars) return true;
        if (!earlyCommitPunctuation) return false;
        String s = buf.toString();
        return s.indexOf('？') >= 0 || s.indexOf('?') >= 0 || s.indexOf('。') >= 0 || s.indexOf('.') >= 0 || s.indexOf('!') >= 0 || s.indexOf('！') >= 0;
    }

    private void rescheduleSoftEndpoint(Consumer<String> onQuestion,
                                        Consumer<String> onAnswerDelta,
                                        Consumer<Throwable> onError,
                                        Runnable onAnswerComplete) {
        cancelSoftEndpoint();
        softEndpointTask = scheduler.schedule(() -> {
            try {
                String segment = drainPartialBuffer();
                if (!segment.isEmpty()) {
                    processSegment(segment, onQuestion, onAnswerDelta, onAnswerComplete, onError);
                }
            } catch (Exception e) {
                log.warn("Soft endpoint process failed", e);
            }
        }, softEndpointMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelSoftEndpoint() {
        try {
            if (softEndpointTask != null) {
                softEndpointTask.cancel(false);
                softEndpointTask = null;
            }
        } catch (Exception ignored) {}
    }

    private String drainPartialBuffer() {
        String s = partialBuffer.toString().trim();
        partialBuffer.setLength(0);
        return s == null ? "" : s;
    }

    private void clearPartialBuffer() {
        partialBuffer.setLength(0);
    }

    private void processSegment(String segment,
                                Consumer<String> onQuestion,
                                Consumer<String> onAnswerDelta,
                                Runnable onAnswerComplete,
                                Consumer<Throwable> onError) {
        String normSeg = normalize(segment);
        if (isSimilarToRecentCommitted(normSeg)) {
            log.debug("Skip partial segment due to similarity with recent committed");
            return;
        }
        try {
            String ctxStr = buildContextString();
            String question = llmClient.extractQuestion(segment, ctxStr);
            String normQ = normalize(question);
            String normLast = normalize(lastQuestion);
            boolean isNoQuestion = question != null && "无问题".equals(question.trim());
            boolean isNewEnough = normQ != null && !normQ.isEmpty() && !normQ.equals(normLast) && jaccardSimilarity(question, lastQuestion) < similarityThreshold;
            if (!isNoQuestion && isNewEnough) {
                lastQuestion = question;
                addRecentQuestion(normQ);
                if (onQuestion != null) onQuestion.accept(question);
            }
            if (answerOnlyOnQuestion && (isNoQuestion || !isNewEnough)) {
                return;
            }
            String inputForAnswer = (question != null && !isNoQuestion) ? question : segment;
            if (llmStreamingEnabled) {
                llmClient.generateAnswerStream(
                        inputForAnswer,
                        ctxStr,
                        delta -> { if (onAnswerDelta != null) onAnswerDelta.accept(delta); },
                        () -> { if (onAnswerComplete != null) onAnswerComplete.run(); },
                        ex -> { if (onError != null) onError.accept(ex); }
                );
            } else {
                String answer = llmClient.generateAnswer(inputForAnswer, ctxStr);
                if (onAnswerDelta != null && answer != null) onAnswerDelta.accept(answer);
                if (onAnswerComplete != null) onAnswerComplete.run();
            }
            rememberCommitted(normSeg);
        } catch (Exception ex) {
            log.warn("LLM processing failed", ex);
            if (onError != null) onError.accept(ex);
        }
    }

    private void rememberCommitted(String norm) {
        if (norm == null || norm.isEmpty()) return;
        if (recentCommittedNorm.size() >= 3) recentCommittedNorm.pollFirst();
        recentCommittedNorm.offerLast(norm);
    }

    private boolean isSimilarToRecentCommitted(String normText) {
        if (normText == null || normText.isEmpty()) return false;
        for (String s : recentCommittedNorm) {
            if (s.equals(normText)) return true;
            if (jaccardSimilarity(s, normText) >= 0.85) return true;
        }
        return false;
    }
}