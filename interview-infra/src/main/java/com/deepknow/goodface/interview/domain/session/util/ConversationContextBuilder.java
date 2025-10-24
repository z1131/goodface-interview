package com.deepknow.goodface.interview.domain.session.util;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话上下文构建器：维护最近问题/陈述与用户提示词，生成上下文字符串。
 */
public class ConversationContextBuilder {
    private final String userPrompt;
    private final int contextWindowSize;
    private final int maxUtterances;

    private final ArrayDeque<String> recentQuestions;
    private final ArrayDeque<String> recentUtterances;
    // 当前问题的补充信息（ELABORATION 累积）
    private final StringBuilder currentElaboration = new StringBuilder(256);
    // 关键事实表（有限大小，后写覆盖）
    private final LinkedHashMap<String, String> facts = new LinkedHashMap<>();

    // 基础指标
    private int questionAdds = 0;
    private int utteranceAdds = 0;

    public ConversationContextBuilder(String userPrompt, int contextWindowSize, int maxUtterances) {
        this.userPrompt = userPrompt;
        this.contextWindowSize = Math.max(1, contextWindowSize);
        this.maxUtterances = Math.max(1, maxUtterances);
        this.recentQuestions = new ArrayDeque<>(this.contextWindowSize);
        this.recentUtterances = new ArrayDeque<>(this.maxUtterances);
    }

    public void addRecentQuestion(String q) {
        if (q == null || q.isEmpty()) return;
        String last = recentQuestions.peekLast();
        if (last != null && last.equals(q)) return;
        if (recentQuestions.size() >= contextWindowSize) {
            recentQuestions.pollFirst();
        }
        recentQuestions.offerLast(q);
        // 重置当前问题的补充上下文
        currentElaboration.setLength(0);
        questionAdds++;
    }

    public void addRecentUtterance(String u) {
        if (u == null || u.isEmpty()) return;
        if (recentUtterances.size() >= maxUtterances) {
            recentUtterances.pollFirst();
        }
        recentUtterances.offerLast(u);
        utteranceAdds++;
    }

    // 合并补充文本（ELABORATION）到当前问题上下文
    public void addElaborationText(String text) {
        if (text == null || text.isEmpty()) return;
        if (currentElaboration.length() > 0) currentElaboration.append(" | ");
        // 简单去除换行
        currentElaboration.append(text.replace('\n', ' ').trim());
        // 限制长度，避免膨胀
        if (currentElaboration.length() > 500) {
            currentElaboration.setLength(500);
        }
    }

    // 合并关键事实（有限大小，后写覆盖）
    public void mergeFacts(Map<String, String> newFacts) {
        if (newFacts == null || newFacts.isEmpty()) return;
        for (Map.Entry<String, String> e : newFacts.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.isEmpty()) continue;
            facts.put(k, v == null ? "" : v);
            // 限制条目数到 20
            if (facts.size() > 20) {
                // 移除最早插入的条目
                String firstKey = facts.keySet().iterator().next();
                facts.remove(firstKey);
            }
        }
    }

    public String getRollingSummary() {
        StringBuilder sb = new StringBuilder(256);
        if (currentElaboration.length() > 0) {
            sb.append("当前问题补充：").append(currentElaboration);
        }
        if (!facts.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("关键事实：");
            boolean first = true;
            for (Map.Entry<String, String> e : facts.entrySet()) {
                if (!first) sb.append(" | ");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    public String buildContextString() {
        if (recentQuestions.isEmpty() && recentUtterances.isEmpty() && (userPrompt == null || userPrompt.isEmpty())
                && currentElaboration.length() == 0 && facts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        if (userPrompt != null && !userPrompt.isEmpty()) {
            sb.append("用户提示词：").append(userPrompt).append("\n");
        }
        if (!recentQuestions.isEmpty()) {
            sb.append("最近问题：");
            boolean first = true;
            for (String q : recentQuestions) {
                if (!first) sb.append(" | ");
                sb.append(q);
                first = false;
            }
        }
        if (currentElaboration.length() > 0) {
            sb.append("\n当前问题补充：").append(currentElaboration);
        }
        if (!recentUtterances.isEmpty()) {
            sb.append("\n最近陈述：");
            boolean first = true;
            for (String u : recentUtterances) {
                if (!first) sb.append(" | ");
                sb.append(u);
                first = false;
            }
        }
        if (!facts.isEmpty()) {
            sb.append("\n关键事实：");
            boolean first = true;
            for (Map.Entry<String, String> e : facts.entrySet()) {
                if (!first) sb.append(" | ");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    public Metrics getMetricsSnapshot() {
        return new Metrics(questionAdds, utteranceAdds, recentQuestions.size(), recentUtterances.size(), contextWindowSize, maxUtterances);
    }

    public static class Metrics {
        public final int questionAdds;
        public final int utteranceAdds;
        public final int currentQuestions;
        public final int currentUtterances;
        public final int contextWindowSize;
        public final int maxUtterances;

        public Metrics(int questionAdds, int utteranceAdds, int currentQuestions, int currentUtterances, int contextWindowSize, int maxUtterances) {
            this.questionAdds = questionAdds;
            this.utteranceAdds = utteranceAdds;
            this.currentQuestions = currentQuestions;
            this.currentUtterances = currentUtterances;
            this.contextWindowSize = contextWindowSize;
            this.maxUtterances = maxUtterances;
        }

        @Override
        public String toString() {
            return "Metrics{" +
                    "questionAdds=" + questionAdds +
                    ", utteranceAdds=" + utteranceAdds +
                    ", currentQuestions=" + currentQuestions +
                    ", currentUtterances=" + currentUtterances +
                    ", contextWindowSize=" + contextWindowSize +
                    ", maxUtterances=" + maxUtterances +
                    '}';
        }
    }
}