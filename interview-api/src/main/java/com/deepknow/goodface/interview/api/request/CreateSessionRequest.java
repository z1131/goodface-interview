package com.deepknow.goodface.interview.api.request;

import java.io.Serializable;
import java.util.Map;

public class CreateSessionRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String userId;
    private Map<String, Object> config;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }
}