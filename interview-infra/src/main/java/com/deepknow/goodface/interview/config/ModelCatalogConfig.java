package com.deepknow.goodface.interview.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ModelCatalogProperties.class)
public class ModelCatalogConfig {
}