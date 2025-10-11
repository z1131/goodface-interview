package com.deepknow.goodface.interview.domain.agent;

import com.deepknow.goodface.interview.domain.agent.LLM.AliyunLlmClient;
import com.deepknow.goodface.interview.domain.agent.LLM.MockLlmClient;
import com.deepknow.goodface.interview.domain.agent.STT.AliyunSttClient;
import com.deepknow.goodface.interview.domain.agent.STT.MockSttClient;

/**
 * 简单的客户端工厂：基于 System Properties 选择具体实现。
 * 说明：@ServerEndpoint 不易直接注入 Spring Bean，先复用系统属性方案。
 */
public class AgentFactory {
    public SttClient createStt() {
        String provider = System.getProperty("stt.provider", "mock");
        if ("aliyun".equalsIgnoreCase(provider)) {
            return new AliyunSttClient();
        }
        return new MockSttClient();
    }

    public LlmClient createLlm() {
        String provider = System.getProperty("llm.provider", "aliyun");
        if ("mock".equalsIgnoreCase(provider)) {
            return new MockLlmClient();
        }
        return new AliyunLlmClient();
    }
}