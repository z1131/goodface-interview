package com.deepknow.goodface.interview.api.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class EndSessionRequest implements Serializable {
    private static final long serialVersionUID = -6194430217661489186L;
    private String sessionId;

    public String getSessionId() { return sessionId; }
}