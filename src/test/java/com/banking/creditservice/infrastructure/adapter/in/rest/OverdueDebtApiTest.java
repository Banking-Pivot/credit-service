package com.banking.creditservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.CreditPageResponse;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.creditservice.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Due date and overdue debt. Every test starts on Monday 16 November 2026 in Lima and the helpers
 * open products that pay on day 15, so the first due date is 15 December; the tests move the clock
 * to get past it.
 */
class OverdueDebtApiTest extends PostgresIntegrationTest {

  private static final LocalDate DECEMBER_15 = LocalDate.of(2026, 12, 15);
  private static final LocalDate JANUARY_15 = LocalDate.of(2027, 1, 15);

  /** From 16 November to 15 December, the due date itself. */
  private static final Duration TO_THE_DUE_DATE = Duration.ofDays(29);

  /** From 16 November to 16 December, one day after the due date. */
  private static final Duration PAST_THE_DUE_DATE = Duration.ofDays(30);

  @Nested
  class DueDate {

    @Test
    void aLoanGetsItsDueDateWithTheDisbursement() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");

      assertThat(loan.getDueDate()).isEqualTo(DECEMBER_15);
      assertThat(loan.getOverdue()).isFalse();
      assertDue(loan.getId(), DECEMBER_15, false);
    }

    @Test
    void aCardGetsItsDueDateWithTheFirstChargeOnly() {
      CreditResponse card = openCard(personalCustomer(), "1000.00");
      assertThat(card.getDueDate()).isNull();
      assertThat(card.getOverdue()).isFalse();

      charge(card.getId(), "100.00");
      assertDue(card.getId(), DECEMBER_15, false);

      clock.advance(Duration.ofDays(10));
      charge(card.getId(), "100.00");
      assertDue(card.getId(), DECEMBER_15, false);
    }

    @Test
    void theDueDateIsADayOfTheBusinessZone() {
      // 15 November 23:30 in Lima is already 16 November in UTC.
      clock.set(java.time.Instant.parse("2026-11-16T04:30:00Z"));

      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");

      assertThat(loan.getDueDate())
          .as("strictly after 15 November, the business date")
          .isEqualTo(DECEMBER_15);
      webTestClient
          .get()
          .uri("/api/v1/credits/{id}", loan.getId())
          .exchange()
          .expectBody()
          .jsonPath("$.dueDate")
          .isEqualTo("2026-12-15")
          .jsonPath("$.overdue")
          .isEqualTo(false);
    }

    @Test
    void aPaymentThatLeavesNoDebtClearsIt() {
      CreditResponse card = openCard(personalCustomer(), "1000.00");
      charge(card.getId(), "100.00");

      pay(card.getId(), "100.00");

      assertDue(card.getId(), null, false);
      webTestClient
          .get()
          .uri("/api/v1/credits/{id}", card.getId())
          .exchange()
          .expectBody()
          .jsonPath("$.dueDate")
          .doesNotExist();

      clock.advance(Duration.ofDays(40));
      charge(card.getId(), "50.00");
      assertDue(card.getId(), JANUARY_15, false);
    }

    @Test
    void anOnTimePaymentOfTheMinimumMovesItOneMonthAndOnlyOncePerCycle() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");

      pay(loan.getId(), "50.00");
      assertDue(loan.getId(), JANUARY_15, false);

      clock.advance(Duration.ofDays(1));
      pay(loan.getId(), "500.00");
      assertDue(loan.getId(), JANUARY_15, false);

      clock.set(startOfLimaDay(LocalDate.of(2026, 12, 20)));
      pay(loan.getId(), "22.50");
      assertDue(loan.getId(), LocalDate.of(2027, 2, 15), false);
    }

    @Test
    void aPaymentBelowTheMinimumLowersTheDebtAndKeepsTheDate() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");

      pay(loan.getId(), "49.99");

      assertDue(loan.getId(), DECEMBER_15, false);
      assertMoney(storedDebt(loan.getId()), "950.01");
    }

    @Test
    void theMinimumIsRoundedHalfUp() {
      CreditResponse card = openCard(personalCustomer(), "1000.00");
      // 5 % of 100.10 is 5.005: the minimum is 5.01.
      charge(card.getId(), "100.10");

      pay(card.getId(), "5.00");
      assertDue(card.getId(), DECEMBER_15, false);

      // 5 % of 95.10 is 4.755: the minimum is 4.76.
      pay(card.getId(), "4.76");
      assertDue(card.getId(), JANUARY_15, false);
    }

    @Test
    void aNewPaymentDayOnlyShowsInTheNextDueDate() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");

      webTestClient
          .put()
          .uri("/api/v1/credits/{id}", loan.getId())
          .header(HttpHeaders.IF_MATCH, "\"0\"")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("paymentDueDay", 20))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.dueDate")
          .isEqualTo("2026-12-15")
          .jsonPath("$.overdue")
          .isEqualTo(false);

      pay(loan.getId(), "50.00");
      assertDue(loan.getId(), LocalDate.of(2027, 1, 20), false);

      pay(loan.getId(), "50.00");
      assertDue(loan.getId(), LocalDate.of(2027, 1, 20), false);
    }

    @Test
    void debtFromBeforeDueDatesIsNotOverdueAndGetsItsDateWithTheNextOperation() {
      UUID customer = personalCustomer();
      CreditResponse loan = openPersonalLoan(customer, "1000.00");
      CreditResponse card = openCard(customer, "1000.00");
      charge(card.getId(), "100.00");
      databaseClient.sql("UPDATE credits SET due_date = NULL").then().block();
      clock.advance(Duration.ofDays(400));

      assertDue(loan.getId(), null, false);
      assertDue(card.getId(), null, false);
      assertThat(list("?customerId={id}&overdue=true", customer).getItems()).isEmpty();
      assertThat(list("?customerId={id}&overdue=false", customer).getItems()).hasSize(2);

      // 21 December 2027: the next day 15 is in January 2028.
      pay(loan.getId(), "1.00");
      assertDue(loan.getId(), null, false);
      pay(loan.getId(), "60.00");
      assertDue(loan.getId(), LocalDate.of(2028, 1, 15), false);
      charge(card.getId(), "10.00");
      assertDue(card.getId(), LocalDate.of(2028, 1, 15), false);
    }
  }

  @Nested
  class Overdue {

    @Test
    void theProductIsOverdueFromTheDayAfterItsDueDate() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");

      clock.advance(TO_THE_DUE_DATE);
      assertDue(loan.getId(), DECEMBER_15, false);

      clock.advance(Duration.ofDays(1));
      assertDue(loan.getId(), DECEMBER_15, true);
    }

    @Test
    void aPaymentOfTheMinimumBringsItUpToDate() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");
      clock.advance(PAST_THE_DUE_DATE);

      pay(loan.getId(), "49.99");
      assertDue(loan.getId(), DECEMBER_15, true);

      pay(loan.getId(), "47.50");
      assertDue(loan.getId(), JANUARY_15, false);
    }

    @Test
    void payingEverythingEndsIt() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");
      clock.advance(PAST_THE_DUE_DATE);

      pay(loan.getId(), "1000.00");

      assertDue(loan.getId(), null, false);
    }

    @Test
    void anybodyCanPayAnOverdueProductWithoutCustomerService() {
      UUID owner = personalCustomer();
      CreditResponse loan = openPersonalLoan(owner, "1000.00");
      clock.advance(PAST_THE_DUE_DATE);
      // The payment names the product only: no payer is asked for, and nobody is looked up.
      CUSTOMERS.fail(503);
      int callsBefore = CUSTOMERS.calls();

      pay(loan.getId(), "100.00");

      assertThat(CUSTOMERS.calls()).isEqualTo(callsBefore);
      assertDue(loan.getId(), JANUARY_15, false);
      assertMoney(storedDebt(loan.getId()), "900.00");
    }
  }

  @Nested
  class Rejections {

    @Test
    void aCustomerWithOverdueDebtCannotOpenAProduct() {
      UUID customer = personalCustomer();
      CreditResponse card = openCard(customer, "1000.00");
      charge(card.getId(), "100.00");
      clock.advance(PAST_THE_DUE_DATE);

      assertRuleViolation(
          openRequest(customer, "CREDIT_CARD", "PEN", null, "500.00"), "OVERDUE_DEBT");
      assertRuleViolation(
          openRequest(customer, "PERSONAL_LOAN", "PEN", "500.00", null), "OVERDUE_DEBT");
      assertThat(creditCount()).isEqualTo(1);

      openCard(personalCustomer(), "500.00");
    }

    @Test
    void theCustomerCanOpenAgainOnceUpToDate() {
      UUID customer = personalCustomer();
      CreditResponse card = openCard(customer, "1000.00");
      charge(card.getId(), "100.00");
      clock.advance(PAST_THE_DUE_DATE);
      assertRuleViolation(
          openRequest(customer, "CREDIT_CARD", "PEN", null, "500.00"), "OVERDUE_DEBT");

      pay(card.getId(), "5.00");

      openCard(customer, "500.00");
    }

    @Test
    void debtThatIsNotOverdueDoesNotStopAnOpening() {
      UUID customer = personalCustomer();
      openPersonalLoan(customer, "1000.00");
      clock.advance(TO_THE_DUE_DATE);

      openCard(customer, "500.00");
    }

    @Test
    void theCustomerIsCheckedBeforeTheDebt() {
      UUID customer = personalCustomer();
      openPersonalLoan(customer, "1000.00");
      clock.advance(PAST_THE_DUE_DATE);

      assertRuleViolation(
          openRequest(customer, "BUSINESS_LOAN", "PEN", "500.00", null), "PRODUCT_NOT_ALLOWED");
      assertRuleViolation(
          openRequest(UUID.randomUUID(), "CREDIT_CARD", "PEN", null, "500.00"),
          "CUSTOMER_NOT_FOUND");
    }

    @Test
    void anOverdueCardAcceptsNoChargesUntilItIsPaid() {
      CreditResponse card = openCard(personalCustomer(), "1000.00");
      charge(card.getId(), "100.00");
      clock.advance(PAST_THE_DUE_DATE);
      UUID operationId = UUID.randomUUID();

      assertRuleViolation(chargeRequest(card.getId(), operationId, "10.00"), "CREDIT_OVERDUE");
      assertMoney(storedDebt(card.getId()), "100.00");
      assertThat(movementCount(card.getId())).isEqualTo(1);

      pay(card.getId(), "5.00");

      created(chargeRequest(card.getId(), operationId, "10.00"));
      assertDue(card.getId(), JANUARY_15, false);
      assertMoney(storedDebt(card.getId()), "105.00");
    }
  }

  @Nested
  class Filter {

    @Test
    void overdueFilterKeepsTheProductsThatOweAfterTheirDueDate() {
      UUID customer = businessCustomer();
      CreditResponse lateLoan = openBusinessLoan(customer, "1000.00");
      clock.advance(Duration.ofMinutes(1));
      CreditResponse lateCard = openCard(customer, "1000.00");
      charge(lateCard.getId(), "100.00");
      clock.advance(Duration.ofMinutes(1));
      CreditResponse unusedCard = openCard(customer, "1000.00");
      clock.advance(Duration.ofMinutes(1));
      CreditResponse paidLoan = openBusinessLoan(customer, "1000.00");
      pay(paidLoan.getId(), "50.00");
      UUID other = personalCustomer();
      CreditResponse otherLoan = openPersonalLoan(other, "1000.00");
      // Up to date and of another customer: must not leak into the pages of the first one.
      openCard(other, "1000.00");
      clock.advance(PAST_THE_DUE_DATE);

      assertThat(ids(list("?customerId={id}&overdue=true", customer)))
          .containsExactly(lateLoan.getId(), lateCard.getId());
      assertThat(ids(list("?customerId={id}&overdue=false", customer)))
          .containsExactly(unusedCard.getId(), paidLoan.getId());
      assertThat(ids(list("?customerId={id}", customer))).hasSize(4);
      assertThat(ids(list("?customerId={id}&overdue=true&type=CREDIT_CARD", customer)))
          .containsExactly(lateCard.getId());
      assertThat(ids(list("?customerId={id}&overdue=true&status=CLOSED", customer))).isEmpty();
      assertThat(
              ids(list("?customerId={id}&overdue=true&status=ACTIVE&type=BUSINESS_LOAN", customer)))
          .containsExactly(lateLoan.getId());
      assertThat(ids(list("?overdue=true&size=100&customerId={id}", other)))
          .containsExactly(otherLoan.getId());

      CreditPageResponse everyOverdue =
          webTestClient
              .get()
              .uri("/api/v1/credits?overdue=true")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(CreditPageResponse.class)
              .returnResult()
              .getResponseBody();
      assertThat(everyOverdue.getTotalElements()).isEqualTo(3);
      assertThat(everyOverdue.getItems()).allMatch(CreditResponse::getOverdue);
    }

    @Test
    void theQuestionOfAccountServiceCountsWithoutReadingEverything() {
      UUID customer = businessCustomer();
      openBusinessLoan(customer, "1000.00");
      openBusinessLoan(customer, "2000.00");

      CreditPageResponse upToDate = list("?customerId={id}&overdue=true&size=1", customer);
      assertThat(upToDate.getItems()).isEmpty();
      assertThat(upToDate.getTotalElements()).isZero();

      clock.advance(PAST_THE_DUE_DATE);

      CreditPageResponse late = list("?customerId={id}&overdue=true&size=1", customer);
      assertThat(late.getItems()).hasSize(1);
      assertThat(late.getItems().get(0).getOverdue()).isTrue();
      assertThat(late.getItems().get(0).getDueDate()).isEqualTo(DECEMBER_15);
      assertThat(late.getTotalElements()).isEqualTo(2);
    }

    @Test
    void aValueThatIsNotBooleanIsRejected() {
      assertError(
          webTestClient.get().uri("/api/v1/credits?overdue=maybe").exchange(),
          400,
          "INVALID_REQUEST");
    }
  }

  private void assertDue(UUID creditId, LocalDate dueDate, boolean overdue) {
    CreditResponse credit = credit(creditId);
    assertThat(credit.getDueDate()).isEqualTo(dueDate);
    assertThat(credit.getOverdue()).isEqualTo(overdue);
  }

  private CreditPageResponse list(String query, UUID customer) {
    return webTestClient
        .get()
        .uri("/api/v1/credits" + query, customer)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CreditPageResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static List<UUID> ids(CreditPageResponse page) {
    return page.getItems().stream().map(CreditResponse::getId).toList();
  }

  private static java.time.Instant startOfLimaDay(LocalDate date) {
    return date.atTime(10, 0).atZone(java.time.ZoneId.of("America/Lima")).toInstant();
  }
}
