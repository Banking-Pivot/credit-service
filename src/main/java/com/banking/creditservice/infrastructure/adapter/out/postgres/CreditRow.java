package com.banking.creditservice.infrastructure.adapter.out.postgres;

import com.banking.creditservice.domain.model.Credit;
import com.banking.creditservice.domain.model.CreditStatus;
import com.banking.creditservice.domain.model.CreditType;
import com.banking.creditservice.domain.model.CurrencyCode;
import com.banking.creditservice.domain.model.CustomerType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code credits}. Enums are stored by name. {@code version} is managed by the domain and
 * written with a conditional UPDATE, not by Spring Data optimistic locking.
 */
@Table("credits")
public record CreditRow(
    @Id UUID id,
    UUID customerId,
    CustomerType customerType,
    CreditType creditType,
    CurrencyCode currency,
    BigDecimal principal,
    BigDecimal creditLimit,
    BigDecimal outstandingDebt,
    int paymentDueDay,
    LocalDate dueDate,
    CreditStatus status,
    Instant openedAt,
    Instant updatedAt,
    Instant closedAt,
    long version) {

  static CreditRow from(Credit credit) {
    return new CreditRow(
        credit.getId(),
        credit.getCustomerId(),
        credit.getCustomerType(),
        credit.getType(),
        credit.getCurrency(),
        credit.getPrincipal(),
        credit.getCreditLimit(),
        credit.getOutstandingDebt(),
        credit.getPaymentDueDay(),
        credit.getDueDate(),
        credit.getStatus(),
        credit.getOpenedAt(),
        credit.getUpdatedAt(),
        credit.getClosedAt(),
        credit.getVersion());
  }

  Credit toDomain() {
    return Credit.restore(
        id,
        customerId,
        customerType,
        creditType,
        currency,
        principal,
        creditLimit,
        outstandingDebt,
        paymentDueDay,
        dueDate,
        status,
        openedAt,
        updatedAt,
        closedAt,
        version);
  }
}
