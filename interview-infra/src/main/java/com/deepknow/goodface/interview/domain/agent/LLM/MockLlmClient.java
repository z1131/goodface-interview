package com.deepknow.goodface.interview.domain.agent.LLM;

import com.deepknow.goodface.interview.domain.agent.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 开发用 Mock LLM：不调用外部服务，返回演示用答案。
 */
public class MockLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private String model;
    private boolean streaming;

    @Override
    public void init(String apiKey, String model, double temperature, double topP, int maxTokens, boolean streaming) {
        this.model = model;
        this.streaming = streaming;
        log.info("Mock LLM init: model={} temperature={} topP={} maxTokens={} streaming={}",
                model, temperature, topP, maxTokens, streaming);
    }

    @Override
    public String extractQuestion(String text, String context) {
        if (text == null || text.isEmpty()) return null;
        // 简单规则：返回最后一句作为“问题”；若上下文包含该问题则返回“无问题”
        String[] parts = text.split("[。？！?！]\\n?");
        String q = parts[parts.length - 1].trim();
        if (context != null && !context.isEmpty() && q.length() > 0 && context.contains(q)) {
            log.info("Mock LLM extractQuestion: 命中上下文，输出无问题");
            return "无问题";
        }
        log.info("Mock LLM extractQuestion: {}", preview(q, 80));
        return q.isEmpty() ? text : q;
    }

    @Override
    public String generateAnswer(String question, String context) {
        String answer = "[mock] 建议回答（" + model + "): " + (question == null ? "-" : question);
        log.info("Mock LLM generateAnswer: {}", preview(answer, 120));
        return answer;
    }

    @Override
    public void generateAnswerStream(String question, String context,
                                     java.util.function.Consumer<String> onDelta,
                                     Runnable onComplete,
                                     java.util.function.Consumer<Throwable> onError) {
        String full = generateAnswer(question, context);
        // 将答案拆分为 3 段进行模拟流式输出
        int len = full.length();
        int step = Math.max(1, len / 3);
        for (int i = 1; i <= 3; i++) {
            final int end = Math.min(len, step * i);
            final String chunk = full.substring(step * (i - 1), end);
            scheduler.schedule(() -> {
                try {
                    if (onDelta != null) onDelta.accept(chunk);
                } catch (Exception e) {
                    if (onError != null) onError.accept(e);
                }
            }, 300L * i, TimeUnit.MILLISECONDS);
        }
        scheduler.schedule(() -> { if (onComplete != null) onComplete.run(); }, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        log.info("Mock LLM closed");
    }

    private String preview(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}