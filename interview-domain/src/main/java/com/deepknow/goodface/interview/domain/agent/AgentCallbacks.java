package com.deepknow.goodface.interview.domain.agent;

import java.util.function.Consumer;

/**
 * 聚合面试 Agent 的所有回调，降低参数爆炸与匿名回调复杂度。
 */
public class AgentCallbacks {
    private final Consumer<String> onSttPartial;
    private final Consumer<String> onSttFinal;
    private final Consumer<String> onQuestion;
    private final Consumer<String> onAnswerDelta;
    private final Runnable onAnswerComplete;
    private final Consumer<Throwable> onError;
    private final Runnable onSttReady;

    public AgentCallbacks(Consumer<String> onSttPartial,
                          Consumer<String> onSttFinal,
                          Consumer<String> onQuestion,
                          Consumer<String> onAnswerDelta,
                          Runnable onAnswerComplete,
                          Consumer<Throwable> onError,
                          Runnable onSttReady) {
        this.onSttPartial = onSttPartial;
        this.onSttFinal = onSttFinal;
        this.onQuestion = onQuestion;
        this.onAnswerDelta = onAnswerDelta;
        this.onAnswerComplete = onAnswerComplete;
        this.onError = onError;
        this.onSttReady = onSttReady;
    }

    public static AgentCallbacks of(Consumer<String> onSttPartial,
                                    Consumer<String> onSttFinal,
                                    Consumer<String> onQuestion,
                                    Consumer<String> onAnswerDelta,
                                    Runnable onAnswerComplete,
                                    Consumer<Throwable> onError,
                                    Runnable onSttReady) {
        return new AgentCallbacks(onSttPartial, onSttFinal, onQuestion, onAnswerDelta, onAnswerComplete, onError, onSttReady);
    }

    public Consumer<String> getOnSttPartial() { return onSttPartial; }
    public Consumer<String> getOnSttFinal() { return onSttFinal; }
    public Consumer<String> getOnQuestion() { return onQuestion; }
    public Consumer<String> getOnAnswerDelta() { return onAnswerDelta; }
    public Runnable getOnAnswerComplete() { return onAnswerComplete; }
    public Consumer<Throwable> getOnError() { return onError; }
    public Runnable getOnSttReady() { return onSttReady; }
}