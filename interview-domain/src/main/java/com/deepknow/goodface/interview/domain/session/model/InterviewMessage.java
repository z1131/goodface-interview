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
}