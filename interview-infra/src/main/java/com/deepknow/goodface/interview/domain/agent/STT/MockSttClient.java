package com.deepknow.goodface.interview.domain.agent.STT;

import com.deepknow.goodface.interview.domain.agent.SttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 开发用 Mock STT：不解析音频，简单回调演示流程。
 */
public class MockSttClient implements SttClient {
    private static final Logger log = LoggerFactory.getLogger(MockSttClient.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private Consumer<String> onPartial;
    private Consumer<String> onFinal;
    private Consumer<Throwable> onError;

    @Override
    public void init(String apiKey, String model, int sampleRate, String language) {
        log.info("Mock STT init: model={} sampleRate={} language={}", model, sampleRate, language);
    }

    @Override
    public void startSession(String sessionId, Consumer<String> onPartial, Consumer<String> onFinal, Consumer<Throwable> onError) {
        this.onPartial = onPartial;
        this.onFinal = onFinal;
        this.onError = onError;
        log.info("Mock STT session started: {}", sessionId);
        // 演示：稍后推送一条 partial 和一条 final 文本
        scheduler.schedule(() -> { if (this.onPartial != null) this.onPartial.accept("[mock] 正在识别..."); }, 300, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> { if (this.onFinal != null) this.onFinal.accept("[mock] 这是模拟的最终识别文本"); }, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void sendAudio(byte[] pcmChunk) {
        // mock 下不处理音频
    }

    @Override
    public void flush() {}

    @Override
    public void close() { log.info("Mock STT closed"); }
}