package com.deepknow.goodface.interview.domain.session.model;

import lombok.Data;

import java.util.Map;

/**
 * 会话上下文：承载当前会话的基本信息与配置。
 */
@Data
public class SessionContext {
    private final String sessionId;
    private final String userId;
    private final InterviewSession session;
    private final Map<String, Object> config;

    public SessionContext(String sessionId, String userId, InterviewSession session, Map<String, Object> config) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.session = session;
        this.config = config;
    }

}