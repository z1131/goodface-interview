package com.deepknow.goodface.interview.stt;

import java.util.function.Consumer;

public interface SttClient extends AutoCloseable {
    void init(String apiKey, String model, int sampleRate, String language);
    void startSession(String sessionId, Consumer<String> onPartial, Consumer<String> onFinal, Consumer<Throwable> onError);
    void sendAudio(byte[] pcmChunk);
    void flush();
    @Override
    void close();
}