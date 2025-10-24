package com.deepknow.goodface.interview.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepknow.goodface.interview.domain.session.service.AudioStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import org.springframework.stereotype.Component;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@ServerEndpoint(value = "/audio/stream")
public class AudioStreamWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(AudioStreamWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 替换原来的 @Autowired 字段为静态字段，配合 Injector 进行注入
    private static AudioStreamService audioStreamService;
    public static void setAudioStreamService(AudioStreamService service) { audioStreamService = service; }
    private static AudioStreamService ensureService() {
        if (audioStreamService == null) {
            try { audioStreamService = SpringContextHolder.getBean(AudioStreamService.class); }
            catch (Exception ignored) {}
        }
        return audioStreamService;
    }

    @OnOpen
    public void onOpen(Session session) {
        log.info("WS connected: {}", session != null ? session.getId() : null);
        if (ensureService() == null) {
            String wsId = session != null ? session.getId() : null;
            log.warn("AudioStreamService not injected; refuse open for ws {}", wsId);
            try {
                if (session != null) session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "SERVICE_NOT_READY"));
            } catch (Exception ignored) {}
            return;
        }
        String sessionId = parseQueryParam(session, "sessionId");
        audioStreamService.open(
                session.getId(),
                sessionId,
                partial -> {
                    // 不再向前端推送 STT 局部文本，避免误导 UI
                },
                fin -> {
                    // 不再向前端推送 STT 最终文本，由 LLM 识别问题后再推送
                },
                q -> {
                    try {
                        log.info("WS push question: len={} preview=\"{}\"",
                                q == null ? 0 : q.length(), preview(q, 120));
                        sendJson(session, Map.of("type", "question", "content", q));
                    } catch (IOException e) { log.warn("send question failed", e); }
                },
                delta -> {
                    try {
                        log.info("WS push answer delta: len={} preview=\"{}\"",
                                delta == null ? 0 : delta.length(), preview(delta, 100));
                        log.debug("WS push answer delta content: {}", delta);
                        sendJson(session, Map.of("type", "answer", "content", delta));
                    } catch (IOException e) { log.warn("send answer delta failed", e); }
                },
                () -> {
                    try {
                        log.info("WS push answer complete: send [END]");
                        sendJson(session, Map.of("type", "answer", "content", "[END]"));
                    } catch (IOException e) { log.warn("send answer end failed", e); }
                },
                ex -> {
                    log.warn("Agent error: {}", ex.getMessage(), ex);
                    try { sendJson(session, Map.of("type", "error", "code", "AGENT_ERROR", "message", ex.getMessage())); }
                    catch (IOException ignored) {}
                },
                () -> {
                    try { sendJson(session, Map.of("type", "stt_ready")); }
                    catch (IOException e) { log.warn("Failed to send stt_ready", e); }
                }
        );
    }

    @OnMessage
    public void onBinaryMessage(ByteBuffer message, Session session) {
        log.trace("Received audio chunk, bytes={}", message.remaining());
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        if (ensureService() == null) {
            log.warn("AudioStreamService not injected; drop audio chunk for ws {}", session != null ? session.getId() : "null");
            return;
        }
        if (session == null) {
            log.warn("Session is null in onBinaryMessage; drop audio chunk");
            return;
        }
        audioStreamService.onAudio(session.getId(), bytes);
    }

    @OnMessage
    public void onTextMessage(String text, Session session) {
        log.trace("Received text: {}", text);
        // 控制帧示例：{"type":"control","action":"end"}
        // 可根据需要扩展暂停/恢复等
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        String wsId = session != null ? session.getId() : null;
        log.info("WS closed: {} status={}", wsId, reason);
        if (ensureService() == null) {
            log.warn("AudioStreamService not injected; skip flush/close for ws {}", wsId);
            return;
        }
        if (wsId == null) {
            log.warn("Session is null in onClose; skip flush/close");
            return;
        }
        audioStreamService.flush(wsId);
        audioStreamService.close(wsId);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        String msg = throwable != null ? throwable.getMessage() : "unknown";
        log.warn("WS error: {}", msg, throwable);
        String wsId = session != null ? session.getId() : null;
        if (ensureService() == null) {
            log.warn("AudioStreamService not injected; skip close for ws {}", wsId);
            return;
        }
        if (wsId == null) {
            log.warn("Session is null in onError; skip close");
            return;
        }
        audioStreamService.close(wsId);
    }

    private String parseQueryParam(Session session, String key) {
        try {
            java.net.URI uri = session.getRequestURI();
            String query = uri.getQuery();
            if (query == null || query.isEmpty()) return null;
            String[] pairs = query.split("&");
            for (String p : pairs) {
                int i = p.indexOf('=');
                if (i > 0) {
                    String k = p.substring(0, i);
                    if (key.equals(k)) return p.substring(i + 1);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse query param {}", key);
        }
        return null;
    }

    private void sendJson(Session session, java.util.Map<String, Object> obj) throws IOException {
        if (session != null && session.isOpen()) {
            String json = objectMapper.writeValueAsString(obj);
            session.getBasicRemote().sendText(json);
        }
    }

    private String preview(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\n", " ");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

}