package com.deepknow.goodface.interview.domain.agent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 统一的 Agent 调度器提供者，用于管理跨 Agent 的定时与延迟任务。
 */
public final class AgentSchedulers {
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);

    private AgentSchedulers() {}

    public static ScheduledExecutorService get() {
        return SCHEDULER;
    }
}