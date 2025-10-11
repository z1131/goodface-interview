package com.deepknow.goodface.interview.domain.agent.STT;

import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerParam;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerRealtime;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.deepknow.goodface.interview.domain.agent.SttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * 阿里云百炼语音 STT 客户端（官方 Java SDK 接入）。
 * 参考示例：TranslationRecognizerRealtime，发送 ByteBuffer 音频帧并以回调接收转写结果。
 */
public class AliyunSttClient implements SttClient {
    private static final Logger log = LoggerFactory.getLogger(AliyunSttClient.class);

    private String apiKey;
    private String model;
    private int sampleRate;
    private String language;

    private Consumer<String> onPartial;
    private Consumer<String> onFinal;
    private Consumer<Throwable> onError;

    private TranslationRecognizerRealtime translator;
    private TranslationRecognizerParam param;

    // 就绪门控与缓冲
    private volatile boolean ready = false;
    private volatile boolean startupFailed = false;
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();
    private final int maxPendingFrames = 50;

    @Override
    public void init(String apiKey, String model, int sampleRate, String language) {
        this.apiKey = apiKey;
        this.model = model == null ? "gummy-realtime-v1" : model;
        this.sampleRate = sampleRate <= 0 ? 16000 : sampleRate;
        this.language = language == null ? "zh-CN" : language;

        var builder = TranslationRecognizerParam.builder()
                .model(this.model)
                .format("pcm")
                .sampleRate(this.sampleRate)
                .transcriptionEnabled(true)
                .sourceLanguage("auto");
        // 如果未通过环境变量配置 API Key，可在此显式设置
        if (this.apiKey != null && !this.apiKey.isEmpty()) {
            builder.apiKey(this.apiKey);
        }
        this.param = builder.build();
    }

    @Override
    public void startSession(String sessionId, Consumer<String> onPartial, Consumer<String> onFinal, Consumer<Throwable> onError) {
        this.onPartial = onPartial;
        this.onFinal = onFinal;
        this.onError = onError;

        try {
            translator = new TranslationRecognizerRealtime();
            ResultCallback<TranslationRecognizerResult> callback = new ResultCallback<>() {
                @Override
                public void onEvent(TranslationRecognizerResult result) {
                    try {
                        if (result.getTranscriptionResult() != null) {
                            String text = result.getTranscriptionResult().getText();
                            boolean isFinal = result.isSentenceEnd();
                            if (text != null && !text.isEmpty()) {
                                if (isFinal) {
                                    if (AliyunSttClient.this.onFinal != null) AliyunSttClient.this.onFinal.accept(text);
                                } else {
                                    if (AliyunSttClient.this.onPartial != null) AliyunSttClient.this.onPartial.accept(text);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Handle transcription result failed", e);
                    }
                }

                @Override
                public void onComplete() {
                    log.info("Aliyun STT transcription complete");
                }

                @Override
                public void onError(Exception e) {
                    log.error("Aliyun STT error", e);
                    startupFailed = true;
                    if (AliyunSttClient.this.onError != null) AliyunSttClient.this.onError.accept(e);
                }
            };
            translator.call(param, callback);
            log.info("Aliyun STT session starting");
            // 简单就绪延时：避免在连接未建立时发送音频导致 idle 错误
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                if (startupFailed) {
                    log.warn("Aliyun STT startup failed; skip ready signal and draining");
                    return;
                }
                ready = true;
                log.info("Aliyun STT ready; draining {} buffered frames", pending.size());
                // 将缓冲的音频帧依次发送
                byte[] buf;
                while ((buf = pending.poll()) != null) {
                    try {
                        translator.sendAudioFrame(ByteBuffer.wrap(buf));
                    } catch (Exception e) {
                        log.warn("Drain buffered frame failed", e);
                    }
                }
            }, "stt-ready-delay").start();
        } catch (Exception e) {
            log.error("Start Aliyun STT session failed", e);
            if (this.onError != null) this.onError.accept(e);
        }
    }

    @Override
    public void sendAudio(byte[] pcmChunk) {
        try {
            if (translator == null) return;
            if (!ready) {
                // 缓冲未就绪的音频帧，设置上限避免无限增长
                if (pending.size() >= maxPendingFrames) {
                    pending.poll();
                }
                pending.offer(Arrays.copyOf(pcmChunk, pcmChunk.length));
                return;
            }
            translator.sendAudioFrame(ByteBuffer.wrap(pcmChunk));
        } catch (Exception e) {
            log.warn("sendAudio failed", e);
            if (onError != null) onError.accept(e);
        }
    }

    @Override
    public void flush() {
        try {
            if (translator != null) {
                translator.stop();
                log.info("Aliyun STT stop sent");
            }
        } catch (Exception e) {
            log.error("Failed to stop STT", e);
            if (onError != null) onError.accept(e);
        }
    }

    @Override
    public void close() {
        try {
            if (translator != null && translator.getDuplexApi() != null) {
                translator.getDuplexApi().close(1000, "bye");
            }
        } catch (Exception e) {
            log.warn("Close Aliyun STT failed", e);
        } finally {
            translator = null;
            ready = false;
            pending.clear();
        }
    }
}