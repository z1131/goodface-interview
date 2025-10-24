package com.deepknow.goodface.interview.domain.session.model;

import lombok.Data;

import java.time.LocalDateTime;


@Data
public class InterviewMessage {
    private Long id;
    private String sessionId;
    private String role; // user, assistant, system
    private String content;
    private LocalDateTime createdAt;

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setRole(String role) { this.role = role; }
    public void setContent(String content) { this.content = content; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}