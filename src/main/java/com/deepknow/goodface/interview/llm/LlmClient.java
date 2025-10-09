package com.deepknow.goodface.interview.llm;

public interface LlmClient extends AutoCloseable {
    void init(String apiKey, String model, double temperature, double topP, int maxTokens, boolean streaming);
    String extractQuestion(String text);
    String generateAnswer(String question, String context);
    /**
     * 直接使用LLM的流式输出，将增量片段通过回调返回。
     */
    void generateAnswerStream(String question, String context,
                              java.util.function.Consumer<String> onDelta,
                              Runnable onComplete,
                              java.util.function.Consumer<Throwable> onError);
    @Override
    void close();
}