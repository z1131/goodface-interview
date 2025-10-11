package com.deepknow.goodface.interview.domain.session;

import com.deepknow.goodface.interview.domain.agent.AgentFactory;
import com.deepknow.goodface.interview.domain.agent.DefaultInterviewAgent;
import com.deepknow.goodface.interview.domain.agent.InterviewAgent;
import com.deepknow.goodface.interview.domain.session.service.AudioStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.deepknow.goodface.interview.domain.session.model.InterviewSession;
import com.deepknow.goodface.interview.domain.session.model.SessionContext;
import com.deepknow.goodface.interview.domain.session.model.InterviewMessage;
import com.deepknow.goodface.interview.repo.mapper.InterviewSessionMapper;
import com.deepknow.goodface.interview.repo.mapper.InterviewMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class AudioStreamServiceImpl implements AudioStreamService {
    private static final Logger log = LoggerFactory.getLogger(AudioStreamService.class);

    private final AgentFactory agentFactory = new AgentFactory();
    private final ConcurrentHashMap<String, InterviewAgent> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> answerBufs = new ConcurrentHashMap<>();

    private final InterviewSessionMapper sessionMapper;
    private final InterviewMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public AudioStreamServiceImpl(InterviewSessionMapper sessionMapper,
                                  InterviewMessageMapper messageMapper,
                                  ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void open(String wsSessionId, String sessionId,
                     Consumer<String> onSttPartial,
                     Consumer<String> onSttFinal,
                     Consumer<String> onAnswerDelta,
                     Runnable onAnswerComplete,
                     Consumer<Throwable> onError,
                     Runnable onSttReady) {
        InterviewSession found = null;
        try {
            found = sessionMapper.findById(sessionId);
        } catch (Exception e) {
            log.warn("Fetch session failed: {}", sessionId, e);
        }
        final InterviewSession session = found;
        if (session == null || session.getStatus() == null || !"ACTIVE".equalsIgnoreCase(session.getStatus())) {
            if (onError != null) onError.accept(new IllegalStateException("SESSION_NOT_ACTIVE"));
            return;
        }

        java.util.Map<String, Object> cfg = java.util.Collections.emptyMap();
        try {
            String cfgJson = session.getConfigJson();
            if (cfgJson != null && !cfgJson.isEmpty()) {
                cfg = objectMapper.readValue(cfgJson, new TypeReference<java.util.Map<String, Object>>(){});
            }
        } catch (Exception e) {
            log.warn("Parse session config failed, use default", e);
        }

        SessionContext ctx = new SessionContext(session.getId(), session.getUserId(), session, cfg);

        InterviewAgent agent = new DefaultInterviewAgent(agentFactory);

        // 建立答案缓冲用于持久化 assistant 完整输出
        StringBuilder buf = new StringBuilder(1024);
        answerBufs.put(wsSessionId, buf);

        agent.start(
                ctx,
                onSttPartial,
                fin -> {
                    if (onSttFinal != null) onSttFinal.accept(fin);
                    try {
                        InterviewMessage msg = new InterviewMessage();
                        msg.setSessionId(session.getId());
                        msg.setRole("user");
                        msg.setContent(fin);
                        msg.setCreatedAt(java.time.LocalDateTime.now());
                        messageMapper.insert(msg);
                    } catch (Exception e) {
                        log.warn("Persist user message failed", e);
                    }
                },
                delta -> {
                    synchronized (buf) { buf.append(delta == null ? "" : delta); }
                    if (onAnswerDelta != null) onAnswerDelta.accept(delta);
                },
                () -> {
                    // 持久化 assistant 完整答案
                    String content;
                    synchronized (buf) { content = buf.toString(); }
                    try {
                        if (content != null && !content.isEmpty()) {
                            InterviewMessage msg = new InterviewMessage();
                            msg.setSessionId(session.getId());
                            msg.setRole("assistant");
                            msg.setContent(content);
                            msg.setCreatedAt(java.time.LocalDateTime.now());
                            messageMapper.insert(msg);
                        }
                    } catch (Exception e) {
                        log.warn("Persist assistant message failed", e);
                    } finally {
                        synchronized (buf) { buf.setLength(0); }
                    }
                    if (onAnswerComplete != null) onAnswerComplete.run();
                },
                ex -> {
                    if (onError != null) onError.accept(ex);
                },
                onSttReady
        );
        sessions.put(wsSessionId, agent);
    }

    @Override
    public void onAudio(String wsSessionId, byte[] pcmChunk) {
        InterviewAgent agent = sessions.get(wsSessionId);
        if (agent != null) {
            agent.sendAudio(pcmChunk);
        } else {
            log.debug("Audio arrived for unknown session {}", wsSessionId);
        }
    }

    @Override
    public void flush(String wsSessionId) {
        InterviewAgent agent = sessions.get(wsSessionId);
        if (agent != null) {
            try { agent.flush(); } catch (Exception e) { log.warn("Flush error", e); }
        }
    }

    @Override
    public void close(String wsSessionId) {
        InterviewAgent agent = sessions.remove(wsSessionId);
        if (agent != null) {
            try { agent.close(); } catch (Exception e) { log.warn("Agent close error", e); }
        }
        answerBufs.remove(wsSessionId);
    }
}