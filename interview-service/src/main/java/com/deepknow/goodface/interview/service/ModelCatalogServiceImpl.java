package com.deepknow.goodface.interview.service;

import com.deepknow.goodface.interview.api.ModelCatalogService;
import com.deepknow.goodface.interview.api.model.ModelInfo;
import com.deepknow.goodface.interview.config.ModelCatalogProperties;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@DubboService
@Service
public class ModelCatalogServiceImpl implements ModelCatalogService {
    private final ModelCatalogProperties props;

    public ModelCatalogServiceImpl(ModelCatalogProperties props) {
        this.props = props;
    }

    @Override
    public List<ModelInfo> listModels() {
        if (props.getAvailable() == null) return java.util.Collections.emptyList();
        return props.getAvailable().stream().map(spec -> {
            ModelInfo m = new ModelInfo();
            m.setId(spec.getId());
            m.setName(spec.getName());
            m.setTier(spec.getTier());
            m.setProvider(spec.getProvider());
            m.setLlmModel(spec.getLlmModel());
            m.setRequiresAuth(spec.isRequiresAuth());
            m.setDescription(spec.getDescription());
            return m;
        }).collect(Collectors.toList());
    }
}