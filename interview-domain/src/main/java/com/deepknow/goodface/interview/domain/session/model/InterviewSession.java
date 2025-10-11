package com.deepknow.goodface.interview.domain.session.model;

import lombok.Data;

import java.time.LocalDateTime;


@Data
public class InterviewSession {
    private String id;
    private String userId;
    private String status; // ACTIVE, ENDED
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String configJson;
}
