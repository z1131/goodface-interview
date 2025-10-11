package com.deepknow.goodface.interview.domain.session;

import com.deepknow.goodface.interview.domain.session.model.InterviewSession;
import com.deepknow.goodface.interview.domain.session.service.SessionService;
import com.deepknow.goodface.interview.repo.mapper.InterviewSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class SessionServiceImpl implements SessionService {
    private final InterviewSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public SessionServiceImpl(InterviewSessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }


    public void createSession(String sessionId, String userId, Map<String, Object> config) {
        InterviewSession s = new InterviewSession();
        s.setId(sessionId);
        s.setUserId(userId);
        s.setStatus("ACTIVE");
        s.setStartTime(LocalDateTime.now());
        s.setEndTime(null);
        try {
            s.setConfigJson(config == null ? "{}" : objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            s.setConfigJson("{}");
        }
        sessionMapper.insert(s);
    }

    public void endSession(String sessionId) {
        sessionMapper.updateStatus(sessionId, "ENDED", LocalDateTime.now());
    }
}