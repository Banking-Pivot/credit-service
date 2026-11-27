package com.banking.creditservice.application.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.domain.model.Credit;
import com.banking.creditservice.domain.model.CreditApplicant;
import com.banking.creditservice.domain.model.CreditTerms;
import com.banking.creditservice.domain.model.CreditType;
import com.banking.creditservice.domain.model.CurrencyCode;
import com.banking.creditservice.domain.model.CustomerType;
import com.banking.creditservice.domain.model.PostingContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The read model copies the due date and decides overdue for the day it is read. */
class CreditViewTest {

  private static final LocalDate OPENED = LocalDate.of(2026, 11, 16);
  private static final LocalDate DUE = LocalDate.of(2026, 12, 15);

  @Test
  void overdueDependsOnTheDayTheProductIsRead() {
    Credit loan =
        Credit.open(
                UUID.randomUUID(),
                new CreditApplicant(UUID.randomUUID(), CustomerType.PERSONAL, true),
                CreditType.PERSONAL_LOAN,
                CurrencyCode.PEN,
                CreditTerms.forOpening(
                    CreditType.PERSONAL_LOAN, new BigDecimal("1000.00"), null, 15),
                new PostingContext(
                    UUID.randomUUID(), Instant.parse("2026-11-16T15:00:00Z"), OPENED, null))
            .credit();

    CreditView onTheDueDate = CreditView.from(loan, DUE);
    CreditView theDayAfter = CreditView.from(loan, DUE.plusDays(1));

    assertThat(onTheDueDate.dueDate()).isEqualTo(DUE);
    assertThat(onTheDueDate.overdue()).isFalse();
    assertThat(theDayAfter.dueDate()).isEqualTo(DUE);
    assertThat(theDayAfter.overdue()).isTrue();
    assertThat(theDayAfter.isCard()).isFalse();
  }
}
