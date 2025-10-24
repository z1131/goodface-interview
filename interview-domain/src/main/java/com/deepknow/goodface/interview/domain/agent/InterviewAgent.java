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
     * 使用聚合回调的便捷启动方法（默认转换为原始方法）。
     */
    default void start(com.deepknow.goodface.interview.domain.session.model.SessionContext ctx,
                       AgentCallbacks callbacks) {
        start(ctx,
                callbacks.getOnSttPartial(),
                callbacks.getOnSttFinal(),
                callbacks.getOnQuestion(),
                callbacks.getOnAnswerDelta(),
                callbacks.getOnAnswerComplete(),
                callbacks.getOnError(),
                callbacks.getOnSttReady());
    }

    /**
     * 使用强类型配置与聚合回调的启动方法（默认忽略配置并转换为原始方法）。
     * 具体 Agent 可以覆盖此方法以使用强类型配置。
     */
    default void start(com.deepknow.goodface.interview.domain.session.model.SessionContext ctx,
                       AgentConfig config,
                       AgentCallbacks callbacks) {
        start(ctx, callbacks);
    }

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