package com.deepknow.goodface.interview.domain.agent.STT;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stt")
public class SttConfigProperties {
    private String provider = "mock"; // aliyun | mock
    private String apiKeyEnv = "DASHSCOPE_API_KEY";
    private String apiKey; // 直接配置的密钥（优先级高于 apiKeyEnv）
    private String model = "gummy-realtime-v1";
    private int sampleRate = 16000;
    private String language = "zh-CN";
    private boolean enableVAD = true;
    private int timeoutMs = 30000;
    private int maxSessionMinutes = 30;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiKeyEnv() { return apiKeyEnv; }
    public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public boolean isEnableVAD() { return enableVAD; }
    public void setEnableVAD(boolean enableVAD) { this.enableVAD = enableVAD; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxSessionMinutes() { return maxSessionMinutes; }
    public void setMaxSessionMinutes(int maxSessionMinutes) { this.maxSessionMinutes = maxSessionMinutes; }
}