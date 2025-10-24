package com.deepknow.goodface.interview.domain.agent;

import com.deepknow.goodface.interview.domain.session.model.SessionContext;

import java.util.Map;

/**
 * 强类型配置：封装 STT/LLM 与上下文策略参数，避免散落的弱类型 Map。
 */
public class AgentConfig {
    // STT
    private final String sttProvider;
    private final String sttApiKeyEnv;
    private final String sttApiKey;
    private final String sttModel;
    private final int sttSampleRate;
    private final String sttLanguage;
    private final int softEndpointMillis;
    private final int maxSegmentChars;
    private final boolean earlyCommitPunctuation;

    // LLM
    private final String llmProvider;
    private final String llmApiKeyEnv;
    private final String llmApiKey;
    private final String llmModel;
    private final double llmTemperature;
    private final double llmTopP;
    private final int llmMaxTokens;
    private final boolean llmStreaming;
    // LLM 相似度门控
    private final boolean llmSimilarityEnabled;
    private final String llmSimilarityPromptVersion;
    private final int llmSimilarityTimeoutMillis;

    // 上下文策略
    private final int contextWindowSize;
    private final String userPrompt;
    private final int maxUtterances;
    private final int debounceMillis;
    private final double similarityThreshold;
    private final boolean answerOnlyOnQuestion;
    // 新增：最小字符阈值与自适应抑制
    private final int minCharsForDetection;
    private final boolean adaptiveSuppression;

    public AgentConfig(String sttProvider,
                       String sttApiKeyEnv, String sttApiKey, String sttModel, int sttSampleRate, String sttLanguage,
                       int softEndpointMillis, int maxSegmentChars, boolean earlyCommitPunctuation,
                       String llmProvider,
                       String llmApiKeyEnv, String llmApiKey, String llmModel, double llmTemperature, double llmTopP,
                       int llmMaxTokens, boolean llmStreaming,
                       boolean llmSimilarityEnabled, String llmSimilarityPromptVersion, int llmSimilarityTimeoutMillis,
                       int contextWindowSize, String userPrompt, int maxUtterances, int debounceMillis,
                       double similarityThreshold, boolean answerOnlyOnQuestion,
                       int minCharsForDetection, boolean adaptiveSuppression) {
        this.sttProvider = sttProvider;
        this.sttApiKeyEnv = sttApiKeyEnv;
        this.sttApiKey = sttApiKey;
        this.sttModel = sttModel;
        this.sttSampleRate = sttSampleRate;
        this.sttLanguage = sttLanguage;
        this.softEndpointMillis = softEndpointMillis;
        this.maxSegmentChars = maxSegmentChars;
        this.earlyCommitPunctuation = earlyCommitPunctuation;
        this.llmProvider = llmProvider;
        this.llmApiKeyEnv = llmApiKeyEnv;
        this.llmApiKey = llmApiKey;
        this.llmModel = llmModel;
        this.llmTemperature = llmTemperature;
        this.llmTopP = llmTopP;
        this.llmMaxTokens = llmMaxTokens;
        this.llmStreaming = llmStreaming;
        this.llmSimilarityEnabled = llmSimilarityEnabled;
        this.llmSimilarityPromptVersion = llmSimilarityPromptVersion;
        this.llmSimilarityTimeoutMillis = llmSimilarityTimeoutMillis;
        this.contextWindowSize = contextWindowSize;
        this.userPrompt = userPrompt;
        this.maxUtterances = maxUtterances;
        this.debounceMillis = debounceMillis;
        this.similarityThreshold = similarityThreshold;
        this.answerOnlyOnQuestion = answerOnlyOnQuestion;
        this.minCharsForDetection = minCharsForDetection;
        this.adaptiveSuppression = adaptiveSuppression;
    }

    public static AgentConfig from(SessionContext ctx) {
        Map<String, Object> cfg = ctx.getConfig();
        String sttProvider = getString(cfg, "stt.provider", System.getProperty("stt.provider", "aliyun"));
        String sttApiKeyEnv = getString(cfg, "stt.apiKeyEnv", System.getProperty("stt.apiKeyEnv", "DASHSCOPE_API_KEY"));
        String sttApiKeyProp = getString(cfg, "stt.apiKey", System.getProperty("stt.apiKey"));
        String sttApiKey = (sttApiKeyProp != null && !sttApiKeyProp.isEmpty()) ? sttApiKeyProp : System.getenv(sttApiKeyEnv);
        String sttModel = getString(cfg, "stt.model", System.getProperty("stt.model", "gummy-realtime-v1"));
        int sttSampleRate = getInt(cfg, "stt.sampleRate", Integer.parseInt(System.getProperty("stt.sampleRate", "16000")));
        String sttLanguage = getString(cfg, "stt.language", System.getProperty("stt.language", "zh-CN"));
        int softEndpointMillis = getInt(cfg, "stt.softEndpointMillis", 1500);
        int maxSegmentChars = getInt(cfg, "stt.maxSegmentChars", 240);
        boolean earlyCommitPunctuation = getBoolean(cfg, "stt.earlyCommitPunctuation", true);

        String llmProvider = getString(cfg, "llm.provider", System.getProperty("llm.provider", "aliyun"));
        String llmApiKeyEnv = getString(cfg, "llm.apiKeyEnv", System.getProperty("llm.apiKeyEnv", "DASHSCOPE_API_KEY"));
        String llmApiKeyProp = getString(cfg, "llm.apiKey", System.getProperty("llm.apiKey"));
        String llmApiKey = (llmApiKeyProp != null && !llmApiKeyProp.isEmpty()) ? llmApiKeyProp : System.getenv(llmApiKeyEnv);
        String llmModel = getString(cfg, "llm.model", System.getProperty("llm.model", "qwen-turbo"));
        double llmTemperature = getDouble(cfg, "llm.temperature", Double.parseDouble(System.getProperty("llm.temperature", "0.5")));
        double llmTopP = getDouble(cfg, "llm.topP", Double.parseDouble(System.getProperty("llm.topP", "0.9")));
        int llmMaxTokens = getInt(cfg, "llm.maxTokens", Integer.parseInt(System.getProperty("llm.maxTokens", "512")));
        boolean llmStreaming = getBoolean(cfg, "llm.streaming", Boolean.parseBoolean(System.getProperty("llm.streaming", "true")));
        boolean llmSimilarityEnabled = getBoolean(cfg, "llmSimilarity.enabled", true);
        String llmSimilarityPromptVersion = getString(cfg, "llmSimilarity.promptVersion", "v1");
        int llmSimilarityTimeoutMillis = getInt(cfg, "llmSimilarity.timeoutMillis", 2000);

        int contextWindowSize = getInt(cfg, "context.windowSize", 3);
        String userPrompt = getString(cfg, "prompt", "");
        int maxUtterances = getInt(cfg, "context.maxUtterances", 5);
        int debounceMillis = getInt(cfg, "context.debounceMillis", 1200);
        double similarityThreshold = getDouble(cfg, "context.similarityThreshold", 0.85);
        boolean answerOnlyOnQuestion = getBoolean(cfg, "context.answerOnlyOnQuestion", true);
        int minCharsForDetection = getInt(cfg, "context.minCharsForDetection", 20);
        boolean adaptiveSuppression = getBoolean(cfg, "context.adaptiveSuppression", true);

        return new AgentConfig(sttProvider,
                sttApiKeyEnv, sttApiKey, sttModel, sttSampleRate, sttLanguage,
                softEndpointMillis, maxSegmentChars, earlyCommitPunctuation,
                llmProvider,
                llmApiKeyEnv, llmApiKey, llmModel, llmTemperature, llmTopP,
                llmMaxTokens, llmStreaming,
                llmSimilarityEnabled, llmSimilarityPromptVersion, llmSimilarityTimeoutMillis,
                contextWindowSize, userPrompt, maxUtterances, debounceMillis, similarityThreshold, answerOnlyOnQuestion,
                minCharsForDetection, adaptiveSuppression);
    }

    private static String getString(Map<String, Object> cfg, String key, String def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        return v == null ? def : String.valueOf(v);
    }
    private static int getInt(Map<String, Object> cfg, String key, int def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
    private static double getDouble(Map<String, Object> cfg, String key, double def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v == null) return def;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }
    private static boolean getBoolean(Map<String, Object> cfg, String key, boolean def) {
        if (cfg == null) return def;
        Object v = cfg.get(key);
        if (v == null) return def;
        try { return Boolean.parseBoolean(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    public String getSttProvider() { return sttProvider; }
    public String getSttApiKeyEnv() { return sttApiKeyEnv; }
    public String getSttApiKey() { return sttApiKey; }
    public String getSttModel() { return sttModel; }
    public int getSttSampleRate() { return sttSampleRate; }
    public String getSttLanguage() { return sttLanguage; }
    public int getSoftEndpointMillis() { return softEndpointMillis; }
    public int getMaxSegmentChars() { return maxSegmentChars; }
    public boolean isEarlyCommitPunctuation() { return earlyCommitPunctuation; }

    public String getLlmProvider() { return llmProvider; }
    public String getLlmApiKeyEnv() { return llmApiKeyEnv; }
    public String getLlmApiKey() { return llmApiKey; }
    public String getLlmModel() { return llmModel; }
    public double getLlmTemperature() { return llmTemperature; }
    public double getLlmTopP() { return llmTopP; }
    public int getLlmMaxTokens() { return llmMaxTokens; }
    public boolean isLlmStreaming() { return llmStreaming; }
    public boolean isLlmSimilarityEnabled() { return llmSimilarityEnabled; }
    public String getLlmSimilarityPromptVersion() { return llmSimilarityPromptVersion; }
    public int getLlmSimilarityTimeoutMillis() { return llmSimilarityTimeoutMillis; }

    public int getContextWindowSize() { return contextWindowSize; }
    public String getUserPrompt() { return userPrompt; }
    public int getMaxUtterances() { return maxUtterances; }
    public int getDebounceMillis() { return debounceMillis; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public boolean isAnswerOnlyOnQuestion() { return answerOnlyOnQuestion; }
    public int getMinCharsForDetection() { return minCharsForDetection; }
    public boolean isAdaptiveSuppression() { return adaptiveSuppression; }
}