package com.banking.creditservice.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code credit.*} settings, served by the Config Server ({@code credit-service.yml}), except the
 * client secret, which comes from Vault.
 *
 * @param businessZone time zone of the business dates: due dates and the product report
 * @param clockOffset time added to the system clock, so a demo can pass a due date without waiting;
 *     zero everywhere else
 * @param billing when a payment counts as the payment of the cycle
 * @param messaging topics and retries of the commands received by Kafka
 * @param clients how this service identifies itself when it calls other services
 */
@Validated
@ConfigurationProperties("credit")
public record CreditProperties(
    @NotNull ZoneId businessZone,
    @NotNull @DefaultValue("0s") Duration clockOffset,
    @NotNull @Valid @DefaultValue Billing billing,
    @NotNull @Valid @DefaultValue Messaging messaging,
    @NotNull @Valid Clients clients) {

  /**
   * Rules of the monthly payment.
   *
   * @param minimumPaymentRate share of the debt before a payment that the payment must reach to
   *     move the due date to the next cycle
   */
  public record Billing(
      @NotNull @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.05")
          BigDecimal minimumPaymentRate) {}

  /**
   * Identity of this service towards the others.
   *
   * @param auth where and with which credentials it gets its access token
   */
  public record Clients(@NotNull @Valid Auth auth) {}

  /**
   * The service token: the API Gateway lets a call through only with a JWT, so before calling
   * another service this one asks auth-service for a token of its own.
   *
   * @param baseUrl address of the API Gateway, which routes the token request to auth-service
   * @param clientId name this service is registered with in auth-service
   * @param clientSecret secret of that client, from Vault ({@code
   *     credit.clients.auth.client-secret})
   * @param refreshMargin how long before its expiry a token stops being used, so a call never
   *     travels with a token about to expire
   */
  public record Auth(
      @NotNull URI baseUrl,
      @NotBlank @DefaultValue("credit-service") String clientId,
      @NotBlank String clientSecret,
      @NotNull @DefaultValue("60s") Duration refreshMargin) {

    /** Without the secret, so it never reaches a log. */
    @Override
    public String toString() {
      return "Auth[baseUrl=" + baseUrl + ", clientId=" + clientId + "]";
    }
  }

  /**
   * Commands that Transaction Service sends by Kafka and the answers to them.
   *
   * @param commandsTopic topic the service reads its commands from
   * @param repliesTopic topic the answers go to
   * @param retryInterval wait before a command that failed for a technical reason is tried again
   * @param maxRetries times such a command is tried again before it is left for Transaction Service
   *     to send once more
   * @param handlingTimeout limit for running a command and, separately, for sending its answer
   */
  public record Messaging(
      @NotBlank @DefaultValue("banking.credit.commands") String commandsTopic,
      @NotBlank @DefaultValue("banking.transaction.replies") String repliesTopic,
      @NotNull @DefaultValue("1s") Duration retryInterval,
      @Min(0) @DefaultValue("3") int maxRetries,
      @NotNull @DefaultValue("10s") Duration handlingTimeout) {}
}
