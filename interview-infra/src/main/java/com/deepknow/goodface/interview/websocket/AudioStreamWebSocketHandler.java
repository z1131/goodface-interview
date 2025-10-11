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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ServerEndpoint(value = "/audio/stream")
public class AudioStreamWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(AudioStreamWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static AudioStreamService audioStreamService;

    public static void setAudioStreamService(AudioStreamService service) {
        audioStreamService = service;
    }

    @OnOpen
    public void onOpen(Session session) {
        log.info("WS connected: {}", session.getId());
        String sessionId = parseQueryParam(session, "sessionId");
        audioStreamService.open(
                session.getId(),
                sessionId,
                partial -> {
                    try { sendJson(session, Map.of("type", "stt_partial", "content", partial)); }
                    catch (IOException e) { log.warn("Failed to send partial", e); }
                },
                fin -> {
                    try { sendJson(session, Map.of("type", "stt_final", "content", fin)); }
                    catch (IOException e) { log.warn("Failed to send final", e); }
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
        log.info("WS closed: {} status={}", session.getId(), reason);
        audioStreamService.flush(session.getId());
        audioStreamService.close(session.getId());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.warn("WS error: {}", throwable.getMessage(), throwable);
        audioStreamService.close(session.getId());
    }

    private String parseQueryParam(Session session, String key) {
        try {
            URI uri = session.getRequestURI();
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

    private void sendJson(Session session, Map<String, Object> obj) throws IOException {
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