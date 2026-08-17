package com.specagent.model.provider;

/**
 * Diagnosable failure categories for OpenCode Zen requests.
 */
public enum OpenCodeModelErrorCategory {
    /** The HTTP request timed out. */
    TIMEOUT,
    /** The connection could not be established or was dropped. */
    CONNECTION,
    /** OpenCode rejected the credential (HTTP 401 / 403). */
    AUTHENTICATION,
    /** OpenCode rate limited the request (HTTP 429). */
    RATE_LIMITED,
    /** OpenCode returned a server error (HTTP 5xx). */
    SERVER_ERROR,
    /** OpenCode returned an unexpected 4xx response. */
    PROVIDER_REQUEST_ERROR,
    /** The response body was malformed or did not match the expected shape. */
    INVALID_RESPONSE,
    /** The completion succeeded but produced no usable content. */
    EMPTY_CONTENT,
    /** The gateway cannot run because a required configuration is missing. */
    NOT_CONFIGURED
}