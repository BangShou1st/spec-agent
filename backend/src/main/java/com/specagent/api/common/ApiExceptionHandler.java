package com.specagent.api.common;

import com.specagent.agent.ModelContractException;
import com.specagent.readmodel.requirement.RequirementStateQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/**
 * Central API exception mapping.
 *
 * <p>Policy:
 *
 * <pre>
 * 400 BAD_REQUEST   malformed request / validation error / invalid UUID
 * 404 NOT_FOUND     project/route/node/answer/spec/run does not exist
 * 409 CONFLICT      request conflicts with runtime lifecycle/state
 * 422               model contract / reflection rejection
 * 500               unexpected internal failure (generic safe message)
 * </pre>
 *
 * <p>Provider/gateway failures are mapped by
 * {@code com.specagent.web.GatewayErrorAdvice} (the API boundary itself never
 * depends on model packages). Unexpected exceptions are logged server-side
 * using only the exception class name so no secret-like content (credentials,
 * provider error payloads) can reach the log, and the client always receives a
 * generic safe message.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(ApiErrorResponse.of(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(RequirementStateQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleRequirementStateQuery(RequirementStateQueryException ex) {
        return switch (ex.reason()) {
            case PROJECT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiErrorResponse.of("PROJECT_NOT_FOUND", "Project not found"));
            case INVARIANT_VIOLATION -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiErrorResponse.of("INTERNAL_INVARIANT_VIOLATION",
                            "The project state failed an internal invariant check"));
        };
    }

    @ExceptionHandler(ModelContractException.class)
    public ResponseEntity<ApiErrorResponse> handleModelContract(ModelContractException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiErrorResponse.of("MODEL_CONTRACT_REJECTED",
                        "The model output did not satisfy the runtime contract"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("VALIDATION_ERROR", "Request validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("MALFORMED_JSON", "Request body is malformed"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        boolean uuidTarget = ex.getRequiredType() != null && UUID.class.isAssignableFrom(ex.getRequiredType());
        if (uuidTarget) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiErrorResponse.of("INVALID_UUID", "Path or query argument must be a valid UUID"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", "Path or query argument has an invalid value"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        LOG.warn("Unhandled API failure of type {}", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR", "An unexpected internal error occurred"));
    }
}