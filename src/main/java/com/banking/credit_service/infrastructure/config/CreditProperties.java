package com.banking.credit_service.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * {@code credit.*} settings, served by the Config Server ({@code credit-service.yml}).
 *
 * @param businessZone time zone of the business dates of the product report
 */
@Validated
@ConfigurationProperties("credit")
public record CreditProperties(@NotNull ZoneId businessZone) {
}
