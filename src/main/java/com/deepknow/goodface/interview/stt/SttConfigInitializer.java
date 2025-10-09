package com.deepknow.goodface.interview.stt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
@EnableConfigurationProperties(SttConfigProperties.class)
public class SttConfigInitializer {
    private final SttConfigProperties props;

    public SttConfigInitializer(SttConfigProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        // 将关键配置同步到 System Properties，便于 @ServerEndpoint 类读取
        System.setProperty("stt.provider", props.getProvider());
        System.setProperty("stt.apiKeyEnv", props.getApiKeyEnv());
        if (props.getApiKey() != null && !props.getApiKey().isEmpty()) {
            System.setProperty("stt.apiKey", props.getApiKey());
        }
        System.setProperty("stt.model", props.getModel());
        System.setProperty("stt.sampleRate", String.valueOf(props.getSampleRate()));
        System.setProperty("stt.language", props.getLanguage());
        System.setProperty("stt.enableVAD", String.valueOf(props.isEnableVAD()));
        System.setProperty("stt.timeoutMs", String.valueOf(props.getTimeoutMs()));
        System.setProperty("stt.maxSessionMinutes", String.valueOf(props.getMaxSessionMinutes()));
    }
}