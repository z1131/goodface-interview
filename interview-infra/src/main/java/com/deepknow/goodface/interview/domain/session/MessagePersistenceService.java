package com.deepknow.goodface.interview.domain.session;

import com.deepknow.goodface.interview.domain.session.model.InterviewMessage;
import com.deepknow.goodface.interview.repo.mapper.InterviewMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.concurrent.*;

@Service
public class MessagePersistenceService {
    private static final Logger logger = LoggerFactory.getLogger(MessagePersistenceService.class);

    private final InterviewMessageMapper messageMapper;
    private final ExecutorService persistExecutor;

    @Autowired
    public MessagePersistenceService(InterviewMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "message-persist-exec");
            t.setDaemon(true);
            return t;
        };
        this.persistExecutor = new ThreadPoolExecutor(
                1,
                1,
                1,
                TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(10000),
                threadFactory,
                (r, e) -> logger.warn("Message persist executor overloaded, content lost")
        );
    }

    public void persistUser(String sessionId, String content) {
        logger.debug("persistUser queued: sessionId={}, len={}", sessionId, content == null ? 0 : content.length());
        persistExecutor.execute(() -> insert(sessionId, "user", content));
    }

    public void persistAssistant(String sessionId, String content) {
        logger.debug("persistAssistant queued: sessionId={}, len={}", sessionId, content == null ? 0 : content.length());
        persistExecutor.execute(() -> insert(sessionId, "assistant", content));
    }

    private void insert(String sessionId, String role, String content) {
        try {
            InterviewMessage msg = new InterviewMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setCreatedAt(java.time.LocalDateTime.now());
            messageMapper.insert(msg);
            logger.info("persist message success: role={}, sessionId={}, len={}", role, sessionId, content == null ? 0 : content.length());
        } catch (Exception ex) {
            logger.warn("persist message failed: role={}, sessionId={}", role, sessionId, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        persistExecutor.shutdown();
    }
}