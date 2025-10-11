package com.deepknow.goodface.interview.api.request;

import java.io.Serializable;

public class EndSessionRequest implements Serializable {
    private String sessionId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}