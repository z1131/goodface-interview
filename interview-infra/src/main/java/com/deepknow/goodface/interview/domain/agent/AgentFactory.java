package com.deepknow.goodface.interview.domain.agent;

import com.deepknow.goodface.interview.domain.agent.LLM.AliyunLlmClient;
import com.deepknow.goodface.interview.domain.agent.STT.AliyunSttClient;

/**
 * 简单的客户端工厂：统一返回阿里云实现。
 */
public class AgentFactory {

    // 基于 AgentConfig 的重载，当前固定阿里云实现
    public SttClient createStt(AgentConfig config) {
        return new AliyunSttClient();
    }

    public LlmClient createLlm(AgentConfig config) {
        return new AliyunLlmClient();
    }
}