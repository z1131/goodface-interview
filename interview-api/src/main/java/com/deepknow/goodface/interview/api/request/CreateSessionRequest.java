package com.deepknow.goodface.interview.api.request;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class CreateSessionRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String userId;
    private Map<String, Object> config;

    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public Map<String, Object> getConfig() { return config; }
}