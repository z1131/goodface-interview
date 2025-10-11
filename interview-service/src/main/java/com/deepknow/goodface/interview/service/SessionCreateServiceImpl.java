package com.deepknow.goodface.interview.service;

import com.deepknow.goodface.interview.api.SessionCreateService;
import com.deepknow.goodface.interview.api.request.CreateSessionRequest;
import com.deepknow.goodface.interview.api.request.EndSessionRequest;
import com.deepknow.goodface.interview.domain.session.service.SessionService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@DubboService
@Service
public class SessionCreateServiceImpl implements SessionCreateService {
    private final SessionService sessionService;

    public SessionCreateServiceImpl(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void createSession(CreateSessionRequest req) {
        sessionService.createSession(req.getSessionId(), req.getUserId(), req.getConfig());
    }

    @Override
    public void endSession(EndSessionRequest req) {
        sessionService.endSession(req.getSessionId());
    }
}