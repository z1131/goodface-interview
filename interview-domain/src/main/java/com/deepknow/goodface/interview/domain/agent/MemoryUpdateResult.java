package com.deepknow.goodface.interview.domain.agent;

import java.util.Collections;
import java.util.Map;

/**
 * LLM 记忆更新结果：滚动摘要与关键事实。
 */
public class MemoryUpdateResult {
    private final String summary;
    private final Map<String, String> facts;

    public MemoryUpdateResult(String summary, Map<String, String> facts) {
        this.summary = summary == null ? "" : summary;
        this.facts = facts == null ? Collections.emptyMap() : facts;
    }

    public String getSummary() { return summary; }
    public Map<String, String> getFacts() { return facts; }

    @Override
    public String toString() {
        return "MemoryUpdateResult{" +
                "summary='" + summary + '\'' +
                ", facts=" + facts +
                '}';
    }
}