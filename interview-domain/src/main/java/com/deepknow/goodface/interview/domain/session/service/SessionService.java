package com.deepknow.goodface.interview.domain.session.service;

import java.util.Map;

public interface SessionService {
    void createSession(String sessionId, String userId, Map<String, Object> config);
    void endSession(String sessionId);
}