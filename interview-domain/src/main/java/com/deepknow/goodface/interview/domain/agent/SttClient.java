package com.deepknow.goodface.interview.domain.agent;

import java.util.function.Consumer;

public interface SttClient extends AutoCloseable {
    void init(String apiKey, String model, int sampleRate, String language);
    void startSession(String sessionId,
                      Consumer<String> onPartial,
                      Consumer<String> onFinal,
                      Consumer<Throwable> onError,
                      Runnable onReady);
    void sendAudio(byte[] pcmChunk);
    void flush();
    @Override
    void close();
}