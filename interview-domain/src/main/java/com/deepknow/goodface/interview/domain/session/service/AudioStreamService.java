package com.deepknow.goodface.interview.domain.session.service;

import java.util.function.Consumer;

/**
 * 会话级音频流编排接口：负责管理 WebSocket 会话与 STT/LLM 的管线调用。
 * 端点层应仅依赖此接口，具体实现放在 infra 层。
 */
public interface AudioStreamService {
    void open(String wsSessionId, String sessionId,
              Consumer<String> onSttPartial,
              Consumer<String> onSttFinal,
              Consumer<String> onQuestion,
              Consumer<String> onAnswerDelta,
              Runnable onAnswerComplete,
              Consumer<Throwable> onError,
              Runnable onSttReady);

    void onAudio(String wsSessionId, byte[] pcmChunk);

    void flush(String wsSessionId);

    void close(String wsSessionId);
}