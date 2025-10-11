package com.deepknow.goodface.interview.api.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class ModelInfo implements Serializable {


    private static final long serialVersionUID = -1621047331479156791L;
    private String id;
    private String name;
    private String tier; // basic | advanced | premium
    private String provider; // aliyun | mock | other
    private String llmModel;
    private boolean requiresAuth;
    private String description;

}