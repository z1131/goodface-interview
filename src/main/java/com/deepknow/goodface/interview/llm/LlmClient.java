package com.deepknow.goodface.interview.llm;

public interface LlmClient extends AutoCloseable {
    void init(String apiKey, String model, double temperature, double topP, int maxTokens, boolean streaming);
    String extractQuestion(String text);
    String generateAnswer(String question, String context);
    @Override
    void close();
}