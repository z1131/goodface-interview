package com.deepknow.goodface.interview.websocket;

import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.websocket.server.ServerContainer;

@Configuration
public class WebSocketConfig implements ServletContextInitializer {

    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        Object attr = servletContext.getAttribute("javax.websocket.server.ServerContainer");
        if (attr instanceof ServerContainer) {
            ServerContainer container = (ServerContainer) attr;
            // 注册基于 javax.websocket 的端点类
            try {
                container.addEndpoint(AudioStreamWebSocketHandler.class);
            } catch (Exception e) {
                // 记录但不抛出，避免启动失败
                servletContext.log("Failed to register WebSocket endpoint: " + e.getMessage());
            }
        }
    }
}