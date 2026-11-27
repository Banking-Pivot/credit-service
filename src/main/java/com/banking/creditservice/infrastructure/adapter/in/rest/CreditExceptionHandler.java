package com.banking.creditservice.infrastructure.adapter.in.rest;

import com.banking.creditservice.application.exception.CreditConcurrentModificationException;
import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.exception.DependencyUnavailableException;
import com.banking.creditservice.application.exception.DuplicatePersonalLoanException;
import com.banking.creditservice.application.exception.InvalidQueryException;
import com.banking.creditservice.application.exception.OperationIdConflictException;
import com.banking.creditservice.domain.exception.CreditClosedException;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.infrastructure.adapter.in.BusinessFailure;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.ErrorResponse;
import com.banking.creditservice.infrastructure.correlation.CorrelationId;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

/**
 * Maps every failure to the contract's ErrorResponse with a stable code, with the same codes and
 * statuses as the other services of the bank.
 *
 * <p>Also receives errors raised before the delegate runs (415 from request mapping, 428 from a
 * missing If-Match, 400 from parameter and body validation), because WebFlux routes dispatch errors
 * to {@code @ExceptionHandler} methods. Messages never echo free text from the request, such as
 * descriptions.
 */
@Slf4j
@RestControllerAdvice
public class CreditExceptionHandler {

  private final Clock clock;

  public CreditExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(CreditNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(
      CreditNotFoundException exception, ServerWebExchange exchange) {
    return error(HttpStatus.NOT_FOUND, BusinessFailure.CREDIT_NOT_FOUND, exchange);
  }

  /** A stale revision in If-Match answers 412 CREDIT_VERSION_CONFLICT. */
  @ExceptionHandler(CreditConcurrentModificationException.class)
  public ResponseEntity<ErrorResponse> handleVersionConflict(
      CreditConcurrentModificationException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.PRECONDITION_FAILED,
        "CREDIT_VERSION_CONFLICT",
        "The credit product changed. Read it again before retrying.",
        exchange);
  }

  @ExceptionHandler(CreditClosedException.class)
  public ResponseEntity<ErrorResponse> handleClosed(
      CreditClosedException exception, ServerWebExchange exchange) {
    return error(HttpStatus.CONFLICT, BusinessFailure.CREDIT_CLOSED, exchange);
  }

  /** An operationId reused with different data answers 409 OPERATION_ID_CONFLICT. */
  @ExceptionHandler(OperationIdConflictException.class)
  public ResponseEntity<ErrorResponse> handleOperationIdConflict(
      OperationIdConflictException exception, ServerWebExchange exchange) {
    return error(HttpStatus.CONFLICT, BusinessFailure.OPERATION_ID_CONFLICT, exchange);
  }

  /**
   * A second active personal loan for the same customer answers 409 PERSONAL_LOAN_ALREADY_EXISTS.
   */
  @ExceptionHandler(DuplicatePersonalLoanException.class)
  public ResponseEntity<ErrorResponse> handleDuplicatePersonalLoan(
      DuplicatePersonalLoanException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.CONFLICT,
        "PERSONAL_LOAN_ALREADY_EXISTS",
        "The customer already has an active personal loan.",
        exchange);
  }

  /** A broken domain rule answers 422 with the name of the rule as code and the domain message. */
  @ExceptionHandler(CreditRuleViolationException.class)
  public ResponseEntity<ErrorResponse> handleBusinessRule(
      CreditRuleViolationException exception, ServerWebExchange exchange) {
    return error(HttpStatus.UNPROCESSABLE_CONTENT, BusinessFailure.ofRule(exception), exchange);
  }

  /**
   * A dependency that cannot answer, such as Customer Service, answers 503 DEPENDENCY_UNAVAILABLE.
   * The log keeps the dependency and the type of the cause, not its message.
   */
  @ExceptionHandler(DependencyUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleDependencyUnavailable(
      DependencyUnavailableException exception, ServerWebExchange exchange) {
    log.warn(
        "Dependency unavailable. corrId={}, dependency={}, causeType={}",
        CorrelationId.of(exchange),
        exception.dependency(),
        exception.getCause() == null ? null : exception.getCause().getClass().getName());
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DEPENDENCY_UNAVAILABLE",
        exception.dependency() + " is unavailable. Nothing was written; retry later.",
        exchange);
  }

  @ExceptionHandler(InvalidQueryException.class)
  public ResponseEntity<ErrorResponse> handleInvalidQuery(
      InvalidQueryException exception, ServerWebExchange exchange) {
    return invalidRequest(exception.getMessage(), exchange);
  }

  /**
   * A missing If-Match answers 428 IF_MATCH_REQUIRED; any other missing parameter or header answers
   * 400 INVALID_REQUEST.
   */
  @ExceptionHandler(MissingRequestValueException.class)
  public ResponseEntity<ErrorResponse> handleMissingValue(
      MissingRequestValueException exception, ServerWebExchange exchange) {
    if (HttpHeaders.IF_MATCH.equalsIgnoreCase(exception.getName())) {
      return error(
          HttpStatus.PRECONDITION_REQUIRED,
          "IF_MATCH_REQUIRED",
          "If-Match header is required. Send the ETag from your last read.",
          exchange);
    }
    return invalidRequest("The request is missing a required value.", exchange);
  }

  /**
   * A body that fails validation answers 400 INVALID_REQUEST naming the rejected fields, never
   * their values.
   */
  @ExceptionHandler(WebExchangeBindException.class)
  public ResponseEntity<ErrorResponse> handleInvalidBody(
      WebExchangeBindException exception, ServerWebExchange exchange) {
    String fields =
        exception.getFieldErrors().stream()
            .map(FieldError::getField)
            .distinct()
            .sorted()
            .collect(Collectors.joining(", "));
    return invalidRequest("Invalid or missing fields: " + fields + ".", exchange);
  }

  @ExceptionHandler(ServerWebInputException.class)
  public ResponseEntity<ErrorResponse> handleInvalidInput(
      ServerWebInputException exception, ServerWebExchange exchange) {
    return invalidRequest(
        "The request is malformed or contains invalid, unknown or missing fields.", exchange);
  }

  /**
   * A constraint violation on a parameter answers 400: INVALID_IF_MATCH when it is on the If-Match
   * header, INVALID_REQUEST otherwise.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(
      ConstraintViolationException exception, ServerWebExchange exchange) {
    boolean ifMatch =
        exception.getConstraintViolations().stream()
            .anyMatch(violation -> violation.getPropertyPath().toString().endsWith(".ifMatch"));
    return ifMatch
        ? invalidIfMatch(exchange)
        : invalidRequest("The request contains invalid parameters.", exchange);
  }

  /**
   * A rejected method argument answers 400: INVALID_IF_MATCH for the If-Match header,
   * INVALID_REQUEST for the rest. A rejected return value is a server fault and answers 500
   * INTERNAL_ERROR.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleMethodValidation(
      HandlerMethodValidationException exception, ServerWebExchange exchange) {
    if (exception.isForReturnValue()) {
      return unexpected(exception, exchange);
    }
    boolean ifMatch =
        exception.getParameterValidationResults().stream()
            .anyMatch(result -> isIfMatchHeader(result.getMethodParameter()));
    return ifMatch
        ? invalidIfMatch(exchange)
        : invalidRequest("The request contains invalid parameters.", exchange);
  }

  /** A request body that is not JSON answers 415 UNSUPPORTED_MEDIA_TYPE. */
  @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
      UnsupportedMediaTypeStatusException exception, ServerWebExchange exchange) {
    return error(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Request body must use application/json.",
        exchange);
  }

  /**
   * A 4xx raised by WebFlux keeps its status and takes the status name as code, for example 405
   * METHOD_NOT_ALLOWED. A 5xx or an unknown status answers 500 INTERNAL_ERROR.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleHttpError(
      ResponseStatusException exception, ServerWebExchange exchange) {
    HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
    if (status == null || status.is5xxServerError()) {
      return unexpected(exception, exchange);
    }
    return error(status, status.name(), status.getReasonPhrase() + ".", exchange);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(
      Exception exception, ServerWebExchange exchange) {
    return unexpected(exception, exchange);
  }

  private ResponseEntity<ErrorResponse> unexpected(
      Exception exception, ServerWebExchange exchange) {
    // No payloads or SQL messages in the log; the type is enough to correlate with the request.
    log.error(
        "Unhandled credit request failure. corrId={}, exceptionType={}",
        CorrelationId.of(exchange),
        exception.getClass().getName());
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "The request could not be completed.",
        exchange);
  }

  private ResponseEntity<ErrorResponse> invalidIfMatch(ServerWebExchange exchange) {
    return error(
        HttpStatus.BAD_REQUEST,
        "INVALID_IF_MATCH",
        "If-Match must be exactly one quoted numeric ETag, for example \"3\".",
        exchange);
  }

  private ResponseEntity<ErrorResponse> invalidRequest(String message, ServerWebExchange exchange) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, exchange);
  }

  private static boolean isIfMatchHeader(MethodParameter parameter) {
    RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
    return header != null
        && HttpHeaders.IF_MATCH.equalsIgnoreCase(
            header.value().isEmpty() ? header.name() : header.value());
  }

  private ResponseEntity<ErrorResponse> error(
      HttpStatus status, BusinessFailure failure, ServerWebExchange exchange) {
    return error(status, failure.code(), failure.message(), exchange);
  }

  private ResponseEntity<ErrorResponse> error(
      HttpStatus status, String code, String message, ServerWebExchange exchange) {
    ErrorResponse response =
        new ErrorResponse()
            .code(code)
            .message(message)
            .timestamp(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .path(exchange.getRequest().getPath().value())
            .traceId(CorrelationId.of(exchange));
    return ResponseEntity.status(status).body(response);
  }
}
