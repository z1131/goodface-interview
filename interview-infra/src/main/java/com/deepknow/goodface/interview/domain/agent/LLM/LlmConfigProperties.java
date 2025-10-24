package com.deepknow.goodface.interview.domain.agent.LLM;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmConfigProperties {
    private String provider = "aliyun"; // aliyun
    private String apiKeyEnv = "DASHSCOPE_API_KEY";
    private String apiKey; // 直接配置的密钥（优先于 apiKeyEnv）
    private String model = "qwen-turbo";
    private double temperature = 0.5;
    private double topP = 0.9;
    private int maxTokens = 512;
    private boolean streaming = true;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKeyEnv() { return apiKeyEnv; }
    public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public double getTopP() { return topP; }
    public void setTopP(double topP) { this.topP = topP; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
}