package com.deepknow.goodface.interview.domain.session;

import com.deepknow.goodface.interview.domain.agent.AgentFactory;
import com.deepknow.goodface.interview.domain.agent.DefaultInterviewAgent;
import com.deepknow.goodface.interview.domain.agent.InterviewAgent;
import com.deepknow.goodface.interview.domain.agent.AgentConfig;
import com.deepknow.goodface.interview.domain.agent.AgentCallbacks;
import com.deepknow.goodface.interview.domain.session.service.AudioStreamService;
import com.deepknow.goodface.interview.domain.session.util.AnswerAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.deepknow.goodface.interview.domain.session.model.InterviewSession;
import com.deepknow.goodface.interview.domain.session.model.SessionContext;
import com.deepknow.goodface.interview.repo.mapper.InterviewSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class AudioStreamServiceImpl implements AudioStreamService {
    private static final Logger logger = LoggerFactory.getLogger(AudioStreamServiceImpl.class);

    private final AgentFactory interviewAgentFactory = new AgentFactory();
    private final ConcurrentHashMap<String, InterviewAgent> activeAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AnswerAccumulator> answerBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> wsToSessionId = new ConcurrentHashMap<>();

    private final InterviewSessionMapper sessionMapper;
    private final MessagePersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AudioStreamServiceImpl(InterviewSessionMapper sessionMapper,
                                  MessagePersistenceService persistenceService,
                                  ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void open(String webSocketSessionId, String interviewSessionId,
                     Consumer<String> sttPartialHandler,
                     Consumer<String> sttFinalHandler,
                     Consumer<String> questionHandler,
                     Consumer<String> answerDeltaHandler,
                     Runnable answerCompleteHandler,
                     Consumer<Throwable> errorHandler,
                     Runnable sttReadyHandler) {
        InterviewSession sessionRecord = null;
        try {
            sessionRecord = sessionMapper.findById(interviewSessionId);
        } catch (Exception e) {
            logger.warn("Fetch session failed: sessionId={}, wsSessionId={}", interviewSessionId, webSocketSessionId, e);
        }
        final InterviewSession interviewSession = sessionRecord;
        if (interviewSession == null || interviewSession.getStatus() == null || !"ACTIVE".equalsIgnoreCase(interviewSession.getStatus())) {
            if (errorHandler != null) errorHandler.accept(new IllegalStateException("SESSION_NOT_ACTIVE"));
            return;
        }

        Map<String, Object> sessionConfig = Collections.emptyMap();
        try {
            String configJson = interviewSession.getConfigJson();
            if (configJson != null && !configJson.isEmpty()) {
                sessionConfig = objectMapper.readValue(configJson, new TypeReference<Map<String, Object>>(){});
            }
        } catch (Exception e) {
            logger.warn("Parse session config failed, use default: sessionId={}, wsSessionId={}", interviewSession.getId(), webSocketSessionId, e);
        }

        SessionContext sessionContext = new SessionContext(interviewSession.getId(), interviewSession.getUserId(), interviewSession, sessionConfig);

        InterviewAgent interviewAgent = new DefaultInterviewAgent(interviewAgentFactory);

        // 建立答案缓冲用于持久化 assistant 完整输出
        AnswerAccumulator answerAcc = new AnswerAccumulator();
        answerBuffers.put(webSocketSessionId, answerAcc);

        // 将各类匿名回调拆分为具名处理器，提升可读性
        Consumer<String> onFinalText = fin -> {
            if (sttFinalHandler != null) {
                sttFinalHandler.accept(fin);
            }
            // 异步持久化用户消息，避免阻塞回调链路
            persistenceService.persistUser(interviewSession.getId(), fin);
        };

        Consumer<String> onAnswerDelta = delta -> {
            answerAcc.append(delta);
            if (answerDeltaHandler != null) {
                answerDeltaHandler.accept(delta);
            }
        };

        Runnable onAnswerComplete = () -> {
            String full = answerAcc.drain();
            persistenceService.persistAssistant(interviewSession.getId(), full);
            if (answerCompleteHandler != null) {
                answerCompleteHandler.run();
            }
        };

        Consumer<Throwable> onError = ex -> {
            if (errorHandler != null) {
                errorHandler.accept(ex);
            }
        };

        AgentConfig agentConfig = AgentConfig.from(sessionContext);
        AgentCallbacks callbacks = AgentCallbacks.of(
                sttPartialHandler,
                onFinalText,
                questionHandler,
                onAnswerDelta,
                onAnswerComplete,
                onError,
                sttReadyHandler
        );
        logger.info("Open session: wsSessionId={}, sessionId={}", webSocketSessionId, interviewSession.getId());
        wsToSessionId.put(webSocketSessionId, interviewSession.getId());
        interviewAgent.start(sessionContext, agentConfig, callbacks);
        activeAgents.put(webSocketSessionId, interviewAgent);
    }

    @Override
    public void onAudio(String webSocketSessionId, byte[] audioPcmChunk) {
        InterviewAgent agent = activeAgents.get(webSocketSessionId);
        if (agent != null) {
            logger.trace("Forward audio: wsSessionId={}, sessionId={}, bytes={}", webSocketSessionId, wsToSessionId.get(webSocketSessionId), audioPcmChunk == null ? 0 : audioPcmChunk.length);
            agent.sendAudio(audioPcmChunk);
        } else {
            String sid = wsToSessionId.get(webSocketSessionId);
            logger.debug("Audio arrived for unknown session: wsSessionId={}, sessionId={}", webSocketSessionId, sid);
        }
    }

    @Override
    public void flush(String webSocketSessionId) {
        InterviewAgent agent = activeAgents.get(webSocketSessionId);
        if (agent != null) {
            try { agent.flush(); } catch (Exception e) { logger.warn("Flush error: wsSessionId={}, sessionId={}", webSocketSessionId, wsToSessionId.get(webSocketSessionId), e); }
        }
    }

    @Override
    public void close(String webSocketSessionId) {
        InterviewAgent agent = activeAgents.remove(webSocketSessionId);
        String sessionId = wsToSessionId.remove(webSocketSessionId);
        if (agent != null) {
            try { agent.close(); } catch (Exception e) { logger.warn("Agent close error: wsSessionId={}, sessionId={}", webSocketSessionId, sessionId, e); }
        }
        logger.info("Closed session: wsSessionId={}, sessionId={}", webSocketSessionId, sessionId);
        answerBuffers.remove(webSocketSessionId);
    }
}