package com.deepknow.goodface.interview.domain.session.model;

import lombok.Data;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * 会话上下文：承载当前会话的基本信息与配置。
 */
@Data
public class SessionContext {
    private String sessionId;
    private String userId;
    private InterviewSession session;
    private Map<String, Object> config;

    public SessionContext() {}

    public SessionContext(String sessionId, String userId, InterviewSession session, Map<String, Object> config) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.session = session;
        this.config = config;
    }

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public InterviewSession getSession() { return session; }
    public Map<String, Object> getConfig() { return config; }
}