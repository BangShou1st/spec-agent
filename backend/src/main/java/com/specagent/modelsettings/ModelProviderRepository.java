package com.specagent.modelsettings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ModelProviderRepository.java
 *
 * 用途:模型提供商行的仓储端口,统一管理预设与用户自建提供商的读写,
 * 本层不对任何预设做特殊处理,由 Jdbc 实现落地到 model_providers 表。
 */
public interface ModelProviderRepository {

    /** 稳定的页面排序:预设在前,用户自建行按 position 排列。 */
    List<ModelProviderRecord> findAll();

    Optional<ModelProviderRecord> findById(UUID id);

    /** 取某预设的第一行,供遗留的按预设访问的门面方法使用。 */
    Optional<ModelProviderRecord> findFirstByPreset(String preset);

    void insert(ModelProviderRecord record);

    void update(ModelProviderRecord record);

    void delete(UUID id);

    /** 记录被验证通过的修订号,激活门禁据此要求精确匹配。 */
    void markValidated(UUID id, long configRevision);
}
