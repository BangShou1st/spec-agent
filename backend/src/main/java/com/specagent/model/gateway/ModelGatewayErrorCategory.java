package com.specagent.model.gateway;

/**
 * Provider-neutral failure categories of the {@link ModelGateway} contract.
 *
 * <p>Gateway implementations map their provider-specific failures onto this
 * vocabulary so the agent reasoning layer can diagnose failures without
 * knowing any concrete provider. Never add provider platform features here.
 */
public enum ModelGatewayErrorCategory {
    /** The HTTP request timed out. */
    TIMEOUT,
    /** The connection could not be established or was dropped. */
    CONNECTION,
    /** The provider rejected the credential (HTTP 401 / 403). */
    AUTHENTICATION,
    /** The provider rate limited the request (HTTP 429). */
    RATE_LIMITED,
    /** The provider returned a server error (HTTP 5xx). */
    SERVER_ERROR,
    /** The provider returned an unexpected 4xx response. */
    PROVIDER_REQUEST_ERROR,
    /** The response body was malformed or did not match the expected shape. */
    INVALID_RESPONSE,
    /** The completion succeeded but produced no usable content. */
    EMPTY_CONTENT,
    /** The gateway cannot run because the configured model is not allowed. */
    INVALID_MODEL,
    /** The gateway cannot run because a required configuration is missing. */
    NOT_CONFIGURED
}