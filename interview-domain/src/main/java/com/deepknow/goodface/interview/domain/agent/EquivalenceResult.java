package com.deepknow.goodface.interview.domain.agent;

/**
 * LLM 判定问题等价性的结果对象。
 * class: SAME / ELABORATION / NEW / NONE
 * canonical: 规范化后的问题文本（当为 SAME/ELABORATION/NEW 时可能提供）
 * reason: 模型给出的简短理由，便于日志与调试
 */
public class EquivalenceResult {
    private final String clazz;
    private final String canonical;
    private final String reason;

    public EquivalenceResult(String clazz, String canonical, String reason) {
        this.clazz = clazz;
        this.canonical = canonical;
        this.reason = reason;
    }

    public String getClazz() { return clazz; }
    public String getCanonical() { return canonical; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return "EquivalenceResult{" +
                "clazz='" + clazz + '\'' +
                ", canonical='" + canonical + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}