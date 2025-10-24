package com.deepknow.goodface.interview.domain.agent;

public interface LlmClient extends AutoCloseable {
    void init(String apiKey, String model, double temperature, double topP, int maxTokens, boolean streaming);
    String extractQuestion(String text, String context);
    String generateAnswer(String question, String context);
    /**
     * 直接使用LLM的流式输出，将增量片段通过回调返回。
     */
    void generateAnswerStream(String question, String context,
                              java.util.function.Consumer<String> onDelta,
                              Runnable onComplete,
                              java.util.function.Consumer<Throwable> onError);

    /**
     * 可选的会话 ID 透传，用于统一日志追踪；默认实现为空操作。
     */
    default void setSessionId(String sessionId) {}

    /**
     * LLM 判定候选问题是否与最近问题同义/补充/全新/无问题。
     * 默认返回 null，表示未实现，由调用方自行降级处理。
     */
    default EquivalenceResult judgeQuestionEquivalence(String lastQuestion, String candidate, String context) { return null; }
    /**
     * 当抽取不到明确问题时，判定该段落与最近问题的关系，仅区分 ELABORATION/NONE。
     * 默认返回 null（未实现）。
     */
    default EquivalenceResult judgeSegmentRelation(String lastQuestion, String segment, String context) { return null; }

    @Override
    void close();
    /**
     * 基于当前问题与上下文，生成滚动摘要与关键事实，用于记忆维护。
     * 默认返回 null（未实现）。
     */
    default MemoryUpdateResult updateContextMemory(String currentQuestion, String accumulatedContext, String recentContext) { return null; }
}