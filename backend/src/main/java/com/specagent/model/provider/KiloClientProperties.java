package com.specagent.model.provider;

import com.sharedlibs.kilogateway.autoconfigure.KiloGatewayProperties;

/**
 * 文件名:KiloClientProperties.java
 *
 * 用途:{@link KiloGatewayProperties} 的复制辅助。每次推理/探测都在保留已配置的
 * base URL、代理与超时的同时,只覆盖凭据与"仅免费"策略;绝不改动注入的共享配置对象。
 */
public final class KiloClientProperties {

    private KiloClientProperties() {
    }

    /**
     * 以共享配置为模板派生一份本次调用专用的配置。
     *
     * @param base     注入的共享配置(只读,不会被修改)
     * @param apiKey   本次调用使用的凭据;为空时匿名调用(仅免费模型可用)
     * @param freeOnly 是否拒绝非免费模型;设置页允许付费模型,故调用方通常传 false
     */
    public static KiloGatewayProperties forCredential(KiloGatewayProperties base, String apiKey,
                                                      boolean freeOnly) {
        KiloGatewayProperties copy = new KiloGatewayProperties();
        copy.setEnabled(base.isEnabled());
        copy.setBaseUrl(base.getBaseUrl());
        copy.setModel(base.getModel());
        copy.setApiKey(apiKey == null ? "" : apiKey);
        copy.setFreeOnly(freeOnly);
        copy.setFallbackModels(base.getFallbackModels());
        copy.setConnectTimeout(base.getConnectTimeout());
        copy.setRequestTimeout(base.getRequestTimeout());
        copy.setProxyHost(base.getProxyHost());
        copy.setProxyPort(base.getProxyPort());
        return copy;
    }
}
