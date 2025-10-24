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

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getStatus() { return status; }
    public String getConfigJson() { return configJson; }

    public void setId(String id) { this.id = id; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setStatus(String status) { this.status = status; }
    public void setStartTime(java.time.LocalDateTime startTime) { this.startTime = startTime; }
    public void setEndTime(java.time.LocalDateTime endTime) { this.endTime = endTime; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
