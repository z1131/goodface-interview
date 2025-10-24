package com.deepknow.goodface.interview.api.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class ModelInfo implements Serializable {


    private static final long serialVersionUID = -1621047331479156791L;
    private String id;
    private String name;
    private String tier; // basic | advanced | premium
    private String provider; // aliyun | other
    private String llmModel;
    private boolean requiresAuth;
    private String description;

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setTier(String tier) { this.tier = tier; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public void setRequiresAuth(boolean requiresAuth) { this.requiresAuth = requiresAuth; }
    public void setDescription(String description) { this.description = description; }
}