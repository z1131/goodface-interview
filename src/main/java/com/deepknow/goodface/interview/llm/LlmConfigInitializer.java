package com.deepknow.goodface.interview.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
@EnableConfigurationProperties(LlmConfigProperties.class)
public class LlmConfigInitializer {
    private final LlmConfigProperties props;

    public LlmConfigInitializer(LlmConfigProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        // 将 LLM 关键配置写入 System Properties，供 @ServerEndpoint 读取
        System.setProperty("llm.provider", props.getProvider());
        System.setProperty("llm.apiKeyEnv", props.getApiKeyEnv());
        if (props.getApiKey() != null && !props.getApiKey().isEmpty()) {
            System.setProperty("llm.apiKey", props.getApiKey());
        }
        System.setProperty("llm.model", props.getModel());
        System.setProperty("llm.temperature", String.valueOf(props.getTemperature()));
        System.setProperty("llm.topP", String.valueOf(props.getTopP()));
        System.setProperty("llm.maxTokens", String.valueOf(props.getMaxTokens()));
        System.setProperty("llm.streaming", String.valueOf(props.isStreaming()));
    }
}