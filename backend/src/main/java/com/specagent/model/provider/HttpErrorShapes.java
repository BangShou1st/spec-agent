package com.specagent.model.provider;

import com.specagent.model.gateway.ModelGatewayErrorCategory;

/** Shared HTTP status to neutral-category mapping. No provider names leak to Agent. */
final class HttpErrorShapes {
    private HttpErrorShapes() {
    }

    static ModelProviderException map(int httpStatus, String bodySnippet, String context) {
        String safe = bodySnippet == null ? "" : (bodySnippet.length() > 300 ? bodySnippet.substring(0, 300) : bodySnippet);
        if (httpStatus == 401 || httpStatus == 403) {
            return new ModelProviderException(ModelGatewayErrorCategory.AUTHENTICATION, context,
                    "Provider rejected the credential (HTTP " + httpStatus + ")", httpStatus);
        }
        if (httpStatus == 404) {
            return new ModelProviderException(ModelGatewayErrorCategory.INVALID_MODEL, context,
                    "Model or endpoint not found (HTTP 404)" + (safe.isBlank() ? "" : ": " + safe), 404);
        }
        if (httpStatus == 429) {
            return new ModelProviderException(ModelGatewayErrorCategory.RATE_LIMITED, context,
                    "Provider rate limited the request (HTTP 429)", 429);
        }
        if (httpStatus >= 500) {
            return new ModelProviderException(ModelGatewayErrorCategory.SERVER_ERROR, context,
                    "Provider unavailable (HTTP " + httpStatus + ")", httpStatus);
        }
        if (httpStatus == 400 || httpStatus == 422) {
            return new ModelProviderException(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR, context,
                    "Provider rejected the request (HTTP " + httpStatus + ")" + (safe.isBlank() ? "" : ": " + safe), httpStatus);
        }
        return new ModelProviderException(ModelGatewayErrorCategory.PROVIDER_REQUEST_ERROR, context,
                "Provider request failed (HTTP " + httpStatus + ")", httpStatus);
    }
}
