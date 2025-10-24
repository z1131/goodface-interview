package com.deepknow.goodface.interview.domain.agent;

import com.deepknow.goodface.interview.domain.session.model.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.deepknow.goodface.interview.domain.agent.strategy.SttSegmentAssembler;
import com.deepknow.goodface.interview.domain.session.util.ConversationContextBuilder;

/**
 * 默认 Agent 实现：接收 STT 最终文本，触发 LLM 问题提取与答案生成（支持流式）。
 */
public class DefaultInterviewAgent implements InterviewAgent {
    private static final Logger log = LoggerFactory.getLogger(DefaultInterviewAgent.class);
    private static final java.util.concurrent.ScheduledExecutorService scheduler = com.deepknow.goodface.interview.domain.agent.AgentSchedulers.get();

    private final AgentFactory factory;
    private SttClient sttClient;
    private LlmClient llmClient;
    private SttSegmentAssembler segmentAssembler;
    private ConversationContextBuilder ctxBuilder;
    private String sessionId;
    private boolean llmStreamingEnabled = true;
    private boolean llmSimilarityEnabled = true;
    private String lastQuestion;
    private java.util.ArrayDeque<String> recentQuestions;
    private int contextWindowSize = 3;
    private String userPrompt;
    private java.util.ArrayDeque<String> recentUtterances;
    private int maxUtterances = 5;
    private int debounceMillis = 1200;
    private double similarityThreshold = 0.85;
    private int minCharsForDetection = 20;
    private boolean adaptiveSuppression = true;
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
    @Deprecated
    public void start(SessionContext ctx,
                      Consumer<String> onSttPartial,
                      Consumer<String> onSttFinal,
                      Consumer<String> onQuestion,
                      Consumer<String> onAnswerDelta,
                      Runnable onAnswerComplete,
                      Consumer<Throwable> onError,
                      Runnable onSttReady) {
        AgentConfig config = AgentConfig.from(ctx);
        AgentCallbacks callbacks = AgentCallbacks.of(
                onSttPartial,
                onSttFinal,
                onQuestion,
                onAnswerDelta,
                onAnswerComplete,
                onError,
                onSttReady
        );
        start(ctx, config, callbacks);
    }

    @Override
    public void start(SessionContext ctx,
                      AgentConfig config,
                      AgentCallbacks callbacks) {
        // 初始化 STT
        this.sessionId = ctx.getSessionId();
        sttClient = factory.createStt(config);
        sttClient.init(config.getSttApiKey(), config.getSttModel(), config.getSttSampleRate(), config.getSttLanguage());

        // 初始化 LLM
        llmClient = factory.createLlm(config);
        llmClient.setSessionId(this.sessionId);
        llmClient.init(config.getLlmApiKey(), config.getLlmModel(), config.getLlmTemperature(),
                config.getLlmTopP(), config.getLlmMaxTokens(), config.isLlmStreaming());
        this.llmStreamingEnabled = config.isLlmStreaming();
        this.llmSimilarityEnabled = config.isLlmSimilarityEnabled();
        log.info("InterviewAgent starting. sessionId={}", this.sessionId);

        // 上下文构建器
        int ctxWin = config.getContextWindowSize();
        if (ctxWin < 1) ctxWin = 1;
        int maxUtter = config.getMaxUtterances();
        if (maxUtter < 1) maxUtter = 1;
        this.ctxBuilder = new ConversationContextBuilder(config.getUserPrompt(), ctxWin, maxUtter);

        // 策略参数
        this.debounceMillis = config.getDebounceMillis();
        if (this.debounceMillis < 0) this.debounceMillis = 0;
        this.similarityThreshold = config.getSimilarityThreshold();
        this.answerOnlyOnQuestion = config.isAnswerOnlyOnQuestion();
        int softMs = config.getSoftEndpointMillis();
        if (softMs < 300) softMs = 300;
        int maxSeg = config.getMaxSegmentChars();
        if (maxSeg < 50) maxSeg = 50;
        boolean earlyPunc = config.isEarlyCommitPunctuation();
        int minChars = config.getMinCharsForDetection();
        if (minChars < 1) minChars = 1;
        this.minCharsForDetection = minChars;
        this.adaptiveSuppression = config.isAdaptiveSuppression();

        // STT 片段组装器
        this.segmentAssembler = new SttSegmentAssembler(
                scheduler,
                softMs,
                maxSeg,
                earlyPunc,
                segment -> { processSegment(segment,
                        callbacks.getOnQuestion(),
                        callbacks.getOnAnswerDelta(),
                        callbacks.getOnAnswerComplete(),
                        callbacks.getOnError());
                    log.debug("Segment metrics snapshot: {} sessionId={}", segmentAssembler.getMetricsSnapshot(), sessionId);
                },
                e -> log.warn("Soft endpoint process failed. sessionId=" + sessionId, e)
        );

        final boolean[] sttFailed = { false };
        sttClient.startSession(ctx.getSessionId(),
                partial -> {
                    if (callbacks.getOnSttPartial() != null) callbacks.getOnSttPartial().accept(partial);
                    segmentAssembler.onPartial(partial);
                },
                fin -> {
                    if (callbacks.getOnSttFinal() != null) callbacks.getOnSttFinal().accept(fin);
                    segmentAssembler.cancel();
                    segmentAssembler.clear();
                    log.debug("STT assembler metrics snapshot: {} sessionId={}", segmentAssembler.getMetricsSnapshot(), sessionId);
                    addRecentUtterance(fin);
                    synchronized (pendingBuffer) {
                        if (pendingTask != null) {
                            try { pendingTask.cancel(false); } catch (Exception ignore) {}
                            pendingTask = null;
                        }
                        if (pendingBuffer.length() > 0) pendingBuffer.append(' ');
                        pendingBuffer.append(fin);
                        pendingTask = scheduler.schedule(() -> {
                            processPending(
                                    callbacks.getOnQuestion(),
                                    callbacks.getOnAnswerDelta(),
                                    callbacks.getOnAnswerComplete(),
                                    callbacks.getOnError());
                        }, debounceMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                },
                err -> {
                    sttFailed[0] = true;
                    if (callbacks.getOnError() != null) callbacks.getOnError().accept(err);
                },
                () -> { if (callbacks.getOnSttReady() != null) callbacks.getOnSttReady().run(); }
        );
}

    @Override
    public void sendAudio(byte[] pcmChunk) {
        if (sttClient != null) sttClient.sendAudio(pcmChunk);
    }

    @Override
    public void flush() {
        runSafe(() -> { if (sttClient != null) sttClient.flush(); }, "Agent flush error");
    }

    @Override
    public void close() {
        runSafe(() -> { if (sttClient != null) sttClient.close(); }, "Agent STT close error");
        runSafe(() -> { if (llmClient != null) llmClient.close(); }, "Agent LLM close error");
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
        // 委托给上下文构建器
        if (ctxBuilder != null) ctxBuilder.addRecentQuestion(q);
    }

    private String buildContextString() {
        // 委托给上下文构建器
        if (ctxBuilder == null) return "";
        return ctxBuilder.buildContextString();
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
        // 委托给上下文构建器
        if (ctxBuilder != null) ctxBuilder.addRecentUtterance(u);
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
            if (normCombined.length() < minCharsForDetection) { return; }
            String ctxStr = buildContextString();
            String question = llmClient.extractQuestion(combined, ctxStr);
            String normQ = normalize(question);
            String normLast = normalize(lastQuestion);
            boolean isNoQuestion = question != null && "无问题".equals(question.trim());
            boolean isNewEnough;
            if (llmSimilarityEnabled && !isNoQuestion && normQ != null && !normQ.isEmpty()) {
                com.deepknow.goodface.interview.domain.agent.EquivalenceResult eq = llmClient.judgeQuestionEquivalence(lastQuestion, question, ctxStr);
                String clazz = (eq == null || eq.getClazz() == null) ? "SAME" : eq.getClazz().trim().toUpperCase();
                if ("NONE".equals(clazz)) {
                    isNoQuestion = true;
                    isNewEnough = false;
                } else if ("NEW".equals(clazz)) {
                    isNewEnough = true;
                    String canonical = (eq.getCanonical() == null || eq.getCanonical().isEmpty()) ? question : eq.getCanonical();
                    normQ = normalize(canonical);
                    question = canonical;
                } else {
                    isNewEnough = false;
                    if ("ELABORATION".equals(clazz)) {
                        // 累积补充并更新记忆
                        ctxBuilder.addElaborationText(combined);
                        try {
                            com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult mem = llmClient.updateContextMemory(lastQuestion, ctxBuilder.getRollingSummary(), ctxStr);
                            if (mem != null) {
                                String summary = mem.getSummary();
                                if (summary != null && !summary.isEmpty()) ctxBuilder.addElaborationText(summary);
                                java.util.Map<String, String> facts = mem.getFacts();
                                if (facts != null && !facts.isEmpty()) ctxBuilder.mergeFacts(facts);
                            }
                        } catch (Exception e) {
                            log.debug("Memory update error ignored. sessionId={}", sessionId, e);
                        }
                    }
                }
            } else {
                isNewEnough = normQ != null && !normQ.isEmpty() && !normQ.equals(normLast) && jaccardSimilarity(question, lastQuestion) < similarityThreshold;
            }
            // 当无明确问题时，尝试判定是否为对最近问题的补充
            if (isNoQuestion && lastQuestion != null && !lastQuestion.isEmpty()) {
                com.deepknow.goodface.interview.domain.agent.EquivalenceResult rel = llmClient.judgeSegmentRelation(lastQuestion, combined, ctxStr);
                String rClazz = (rel == null || rel.getClazz() == null) ? "NONE" : rel.getClazz().trim().toUpperCase();
                if ("ELABORATION".equals(rClazz)) {
                    ctxBuilder.addElaborationText(combined);
                    try {
                        com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult mem = llmClient.updateContextMemory(lastQuestion, ctxBuilder.getRollingSummary(), ctxStr);
                        if (mem != null) {
                            String summary = mem.getSummary();
                            if (summary != null && !summary.isEmpty()) ctxBuilder.addElaborationText(summary);
                            java.util.Map<String, String> facts = mem.getFacts();
                            if (facts != null && !facts.isEmpty()) ctxBuilder.mergeFacts(facts);
                        }
                    } catch (Exception e) {
                        log.debug("Memory update error ignored. sessionId={}", sessionId, e);
                    }
                }
            }
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
                        () -> { if (onAnswerComplete != null) onAnswerComplete.run(); log.debug("Context metrics snapshot: {} sessionId={}", ctxBuilder.getMetricsSnapshot(), sessionId); },
                        ex -> { if (onError != null) onError.accept(ex); }
                );
            } else {
                String answer = llmClient.generateAnswer(inputForAnswer, ctxStr);
                if (onAnswerDelta != null && answer != null) onAnswerDelta.accept(answer);
                if (onAnswerComplete != null) onAnswerComplete.run();
            }
            rememberCommitted(normCombined);
        } catch (Exception ex) {
            log.warn("LLM processing failed. sessionId=" + sessionId, ex);
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
                log.warn("Soft endpoint process failed. sessionId=" + sessionId, e);
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
            if (normSeg.length() < minCharsForDetection) { return; }
            String ctxStr = buildContextString();
            String question = llmClient.extractQuestion(segment, ctxStr);
            String normQ = normalize(question);
            String normLast = normalize(lastQuestion);
            boolean isNoQuestion = question != null && "无问题".equals(question.trim());
            boolean isNewEnough;
            if (llmSimilarityEnabled && !isNoQuestion && normQ != null && !normQ.isEmpty()) {
                com.deepknow.goodface.interview.domain.agent.EquivalenceResult eq = llmClient.judgeQuestionEquivalence(lastQuestion, question, ctxStr);
                String clazz = (eq == null || eq.getClazz() == null) ? "SAME" : eq.getClazz().trim().toUpperCase();
                if ("NONE".equals(clazz)) {
                    isNoQuestion = true;
                    isNewEnough = false;
                } else if ("NEW".equals(clazz)) {
                    isNewEnough = true;
                    String canonical = (eq.getCanonical() == null || eq.getCanonical().isEmpty()) ? question : eq.getCanonical();
                    normQ = normalize(canonical);
                    question = canonical;
                } else {
                    isNewEnough = false;
                    if ("ELABORATION".equals(clazz)) {
                        // 累积补充并更新记忆
                        ctxBuilder.addElaborationText(segment);
                        try {
                            com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult mem = llmClient.updateContextMemory(lastQuestion, ctxBuilder.getRollingSummary(), ctxStr);
                            if (mem != null) {
                                String summary = mem.getSummary();
                                if (summary != null && !summary.isEmpty()) ctxBuilder.addElaborationText(summary);
                                java.util.Map<String, String> facts = mem.getFacts();
                                if (facts != null && !facts.isEmpty()) ctxBuilder.mergeFacts(facts);
                            }
                        } catch (Exception e) {
                            log.debug("Memory update error ignored. sessionId={}", sessionId, e);
                        }
                    }
                }
            } else {
                isNewEnough = normQ != null && !normQ.isEmpty() && !normQ.equals(normLast) && jaccardSimilarity(question, lastQuestion) < similarityThreshold;
            }
            // 当无明确问题时，尝试判定是否为对最近问题的补充
            if (isNoQuestion && lastQuestion != null && !lastQuestion.isEmpty()) {
                com.deepknow.goodface.interview.domain.agent.EquivalenceResult rel = llmClient.judgeSegmentRelation(lastQuestion, segment, ctxStr);
                String rClazz = (rel == null || rel.getClazz() == null) ? "NONE" : rel.getClazz().trim().toUpperCase();
                if ("ELABORATION".equals(rClazz)) {
                    ctxBuilder.addElaborationText(segment);
                    try {
                        com.deepknow.goodface.interview.domain.agent.MemoryUpdateResult mem = llmClient.updateContextMemory(lastQuestion, ctxBuilder.getRollingSummary(), ctxStr);
                        if (mem != null) {
                            String summary = mem.getSummary();
                            if (summary != null && !summary.isEmpty()) ctxBuilder.addElaborationText(summary);
                            java.util.Map<String, String> facts = mem.getFacts();
                            if (facts != null && !facts.isEmpty()) ctxBuilder.mergeFacts(facts);
                        }
                    } catch (Exception e) {
                        log.debug("Memory update error ignored. sessionId={}", sessionId, e);
                    }
                }
            }
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
                log.debug("Context metrics snapshot: {} sessionId={}", ctxBuilder.getMetricsSnapshot(), sessionId);
            }
            rememberCommitted(normSeg);
        } catch (Exception ex) {
            log.warn("LLM processing failed. sessionId=" + sessionId, ex);
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
        if (!adaptiveSuppression) return false;
        for (String s : recentCommittedNorm) {
            if (s.equals(normText)) return true;
            if (jaccardSimilarity(s, normText) >= 0.85) return true;
        }
        return false;
    }

    private void runSafe(Runnable action, String warnTag) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn(warnTag + ". sessionId=" + sessionId, e);
        }
    }
}