package com.specagent.model.provider;

import com.specagent.model.contract.ModelGatewayErrorCategory;
import com.specagent.model.contract.ModelGatewayException;

/**
 * 文件名:ModelProviderException.java
 *
 * 用途:OpenRouter / Custom 边界的提供商无关失败异常。把每一种协议专属失败
 * 映射到既有的 {@link ModelGatewayErrorCategory} 词汇上,使 Agent 业务代码永远
 * 看不到 {@code ANTHROPIC_429}、{@code OPENROUTER_ERROR} 或
 * {@code RESPONSES_BAD_EVENT} 之类的原始错误。提供商/格式上下文只保留在消息
 * 前缀中(对 Settings API 是安全的),密钥绝不进入消息。
 */
public class ModelProviderException extends ModelGatewayException {

    private final String providerContext;

    public ModelProviderException(ModelGatewayErrorCategory category, String providerContext, String message) {
        this(category, providerContext, message, null, null);
    }

    public ModelProviderException(ModelGatewayErrorCategory category, String providerContext,
                                  String message, Integer httpStatus) {
        this(category, providerContext, message, httpStatus, null);
    }

    public ModelProviderException(ModelGatewayErrorCategory category, String providerContext,
                                  String message, Throwable cause) {
        this(category, providerContext, message, null, cause);
    }

    public ModelProviderException(ModelGatewayErrorCategory category, String providerContext,
                                  String message, Integer httpStatus, Throwable cause) {
        super(category, "[" + providerContext + "] " + message, httpStatus, cause);
        this.providerContext = providerContext;
    }

    public String providerContext() {
        return providerContext;
    }

    public static ModelProviderException notConfigured(String context, String message) {
        return new ModelProviderException(ModelGatewayErrorCategory.NOT_CONFIGURED, context, message);
    }

    public static ModelProviderException authentication(String context, String message, Integer httpStatus) {
        return new ModelProviderException(ModelGatewayErrorCategory.AUTHENTICATION, context, message, httpStatus);
    }

    public static ModelProviderException invalidModel(String context, String message, Integer httpStatus) {
        return new ModelProviderException(ModelGatewayErrorCategory.INVALID_MODEL, context, message, httpStatus);
    }

    public static ModelProviderException rateLimited(String context, String message) {
        return new ModelProviderException(ModelGatewayErrorCategory.RATE_LIMITED, context, message, 429);
    }

    public static ModelProviderException timeout(String context, String message, Throwable cause) {
        return new ModelProviderException(ModelGatewayErrorCategory.TIMEOUT, context, message, null, cause);
    }

    public static ModelProviderException connection(String context, String message, Throwable cause) {
        return new ModelProviderException(ModelGatewayErrorCategory.CONNECTION, context, message, null, cause);
    }

    public static ModelProviderException serverError(String context, String message, Integer httpStatus) {
        return new ModelProviderException(ModelGatewayErrorCategory.SERVER_ERROR, context, message, httpStatus);
    }

    public static ModelProviderException providerRequestError(String context, String message, Integer httpStatus) {
        return new ModelProviderException(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR, context, message, httpStatus);
    }

    public static ModelProviderException invalidResponse(String context, String message) {
        return new ModelProviderException(ModelGatewayErrorCategory.INVALID_RESPONSE, context, message);
    }

    public static ModelProviderException invalidResponse(String context, String message, Throwable cause) {
        return new ModelProviderException(ModelGatewayErrorCategory.INVALID_RESPONSE, context, message, null, cause);
    }
}
