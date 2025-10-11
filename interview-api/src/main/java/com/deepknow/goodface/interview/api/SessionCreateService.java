package com.deepknow.goodface.interview.api;

import com.deepknow.goodface.interview.api.request.CreateSessionRequest;
import com.deepknow.goodface.interview.api.request.EndSessionRequest;

public interface SessionCreateService {
    void createSession(CreateSessionRequest req);
    void endSession(EndSessionRequest req);
}