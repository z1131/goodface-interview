package com.deepknow.goodface.interview.domain.agent;

import java.util.function.Consumer;

/**
 * 通用面试 Agent，将 STT 与 LLM 组合，对外提供统一事件回调。
 */
public interface InterviewAgent extends AutoCloseable {
    /**
     * 启动会话并建立内部 STT/LLM 管线。
     */
    void start(com.deepknow.goodface.interview.domain.session.model.SessionContext ctx,
               Consumer<String> onSttPartial,
               Consumer<String> onSttFinal,
               Consumer<String> onQuestion,
               Consumer<String> onAnswerDelta,
               Runnable onAnswerComplete,
               Consumer<Throwable> onError,
               Runnable onSttReady);

    /**
     * 推送音频帧（PCM）。
     */
    void sendAudio(byte[] pcmChunk);

    /**
     * 刷新并结束 STT。
     */
    void flush();

    @Override
    void close();
}