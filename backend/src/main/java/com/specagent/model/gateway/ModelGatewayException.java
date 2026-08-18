package com.specagent.model.gateway;

/**
 * Provider-neutral failure raised by a {@link ModelGateway} implementation.
 *
 * <p>The agent reasoning layer catches this type and reads
 * {@link #gatewayCategory()} for diagnosis; it must never depend on a concrete
 * provider exception. Messages never contain the API key or the Authorization
 * header value, and the agent trace never persists the message.
 */
public class ModelGatewayException extends RuntimeException {

    private final ModelGatewayErrorCategory gatewayCategory;
    private final Integer httpStatus;

    public ModelGatewayException(ModelGatewayErrorCategory category, String message) {
        this(category, message, null, null);
    }

    public ModelGatewayException(ModelGatewayErrorCategory category, String message, Integer httpStatus) {
        this(category, message, httpStatus, null);
    }

    public ModelGatewayException(ModelGatewayErrorCategory category, String message, Throwable cause) {
        this(category, message, null, cause);
    }

    protected ModelGatewayException(ModelGatewayErrorCategory category,
                                    String message,
                                    Integer httpStatus,
                                    Throwable cause) {
        super(message, cause);
        this.gatewayCategory = category;
        this.httpStatus = httpStatus;
    }

    public ModelGatewayErrorCategory gatewayCategory() {
        return gatewayCategory;
    }

    /**
     * The HTTP status that caused the failure, or null when the failure did not
     * reach the HTTP layer (timeout, connection, invalid response).
     */
    public Integer httpStatus() {
        return httpStatus;
    }
}