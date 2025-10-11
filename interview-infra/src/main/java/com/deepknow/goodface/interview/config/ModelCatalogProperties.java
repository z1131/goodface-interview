package com.deepknow.goodface.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "models")
public class ModelCatalogProperties {
    private List<ModelSpec> available = new ArrayList<>();

    public List<ModelSpec> getAvailable() { return available; }
    public void setAvailable(List<ModelSpec> available) { this.available = available; }

    public static class ModelSpec {
        private String id;
        private String name;
        private String tier;
        private String provider;
        private String llmModel;
        private boolean requiresAuth;
        private String description;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getLlmModel() { return llmModel; }
        public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
        public boolean isRequiresAuth() { return requiresAuth; }
        public void setRequiresAuth(boolean requiresAuth) { this.requiresAuth = requiresAuth; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}