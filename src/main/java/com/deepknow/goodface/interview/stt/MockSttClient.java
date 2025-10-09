package com.deepknow.goodface.interview.stt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MockSttClient implements SttClient {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private Consumer<String> onPartial;
    private Consumer<String> onFinal;
    private Consumer<Throwable> onError;
    private final List<byte[]> buffer = new ArrayList<>();

    @Override
    public void init(String apiKey, String model, int sampleRate, String language) {
    }

    @Override
    public void startSession(String sessionId, Consumer<String> onPartial, Consumer<String> onFinal, Consumer<Throwable> onError) {
        this.onPartial = onPartial;
        this.onFinal = onFinal;
        this.onError = onError;
    }

    @Override
    public void sendAudio(byte[] pcmChunk) {
        buffer.add(pcmChunk);
        String partial = "[partial] bytes=" + pcmChunk.length;
        if (onPartial != null) onPartial.accept(partial);
    }

    @Override
    public void flush() {
        int total = buffer.stream().mapToInt(b -> b.length).sum();
        String finalText = "[final] total_bytes=" + total;
        scheduler.schedule(() -> {
            if (onFinal != null) onFinal.accept(finalText);
        }, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdown();
        buffer.clear();
    }
}