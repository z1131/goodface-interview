package com.deepknow.goodface.interview.api;

import com.deepknow.goodface.interview.api.model.ModelInfo;
import java.util.List;

public interface ModelCatalogService {
    List<ModelInfo> listModels();
}