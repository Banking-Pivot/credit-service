package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.AvailableBalanceResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditDebtResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Charges and payments, their idempotency by operationId, and the debt and balance reads. */
class CreditOperationsApiTest extends PostgresIntegrationTest {

    @Nested
    class Journey {

        @Test
        void chargeAndPaymentMoveDebtAndAvailableCredit() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            UUID chargeId = UUID.randomUUID();

            OperationResponse charged = created(post("/credits/" + card.getId() + "/charges", Map.of(
                    "operationId", chargeId, "amount", new BigDecimal("300.00"), "currency", "PEN",
                    "description", "  Supermarket  ")));

            assertThat(charged.getOperationId()).isEqualTo(chargeId);
            assertThat(charged.getType().getValue()).isEqualTo("CHARGE");
            assertThat(charged.getStatus().getValue()).isEqualTo("COMPLETED");
            assertThat(charged.getCreditId()).isEqualTo(card.getId());
            assertThat(charged.getMovementId()).isNotNull();
            assertMoney(charged.getAmount(), "300.00");
            assertMoney(charged.getOutstandingDebtAfter(), "300.00");
            assertMoney(charged.getAvailableCreditAfter(), "700.00");
            assertThat(charged.getDescription()).isEqualTo("Supermarket");
            assertThat(charged.getOccurredAt().toInstant()).isAfter(card.getOpenedAt().toInstant());

            CreditDebtResponse debt = debt(card.getId());
            assertMoney(debt.getOutstandingDebt(), "300.00");
            assertMoney(debt.getCreditLimit(), "1000.00");
            assertThat(debt.getPrincipal()).isNull();
            AvailableBalanceResponse available = available(card.getId());
            assertMoney(available.getOutstandingDebt(), "300.00");
            assertMoney(available.getAvailableCredit(), "700.00");
            assertThat(available.getAsOf().toInstant()).isEqualTo(clock.instant());

            OperationResponse paid = pay(card.getId(), "100.00");

            assertThat(paid.getType().getValue()).isEqualTo("PAYMENT");
            assertMoney(paid.getOutstandingDebtAfter(), "200.00");
            assertMoney(paid.getAvailableCreditAfter(), "800.00");
            assertMoney(available(card.getId()).getAvailableCredit(), "800.00");
            assertMoney(storedDebt(card.getId()), "200.00");
            assertThat(movementCount(card.getId())).isEqualTo(2);
        }

        @Test
        void revisionChangesWithTheDebt() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");

            charge(card.getId(), "10.00");
            pay(card.getId(), "10.00");

            assertThat(etag(card.getId())).isEqualTo("\"2\"");
        }

        @Test
        void loanIsPaidInInstalments() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "1200.00");

            OperationResponse paid = pay(loan.getId(), "400.00");

            assertMoney(paid.getOutstandingDebtAfter(), "800.00");
            assertThat(paid.getAvailableCreditAfter()).as("loans have no available credit").isNull();
            CreditDebtResponse debt = debt(loan.getId());
            assertMoney(debt.getPrincipal(), "1200.00");
            assertMoney(debt.getOutstandingDebt(), "800.00");
            assertThat(debt.getCreditLimit()).isNull();
        }

        @Test
        void operationsAndReadsDoNotCallCustomerService() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            CUSTOMERS.fail(503);
            int callsAfterOpening = CUSTOMERS.calls();

            charge(card.getId(), "50.00");
            pay(card.getId(), "20.00");
            assertMoney(available(card.getId()).getAvailableCredit(), "970.00");
            assertMoney(debt(card.getId()).getOutstandingDebt(), "30.00");

            assertThat(CUSTOMERS.calls()).isEqualTo(callsAfterOpening);
        }
    }

    @Nested
    class Idempotency {

        @Test
        void repeatReturnsTheOriginalResultEvenAfterLaterOperations() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            UUID operationId = UUID.randomUUID();
            OperationResponse original = created(chargeRequest(card.getId(), operationId, "300.00"));
            charge(card.getId(), "100.00");

            OperationResponse repeated = replayed(chargeRequest(card.getId(), operationId, "300.00"));

            assertThat(repeated).isEqualTo(original);
            assertMoney(repeated.getOutstandingDebtAfter(), "300.00");
            assertMoney(storedDebt(card.getId()), "400.00");
            assertThat(movementCount(card.getId())).isEqualTo(2);
        }

        @Test
        void repeatedPaymentDoesNotPayTwice() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            charge(card.getId(), "300.00");
            UUID operationId = UUID.randomUUID();
            OperationResponse original = created(payRequest(card.getId(), operationId, "100.00"));

            OperationResponse repeated = replayed(payRequest(card.getId(), operationId, "100.00"));

            assertThat(repeated).isEqualTo(original);
            assertMoney(storedDebt(card.getId()), "200.00");
            assertThat(movementCount(card.getId())).isEqualTo(2);
        }

        @Test
        void amountsAreComparedByValue() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            UUID operationId = UUID.randomUUID();
            created(postJson("/credits/" + card.getId() + "/charges",
                    "{\"operationId\":\"" + operationId + "\",\"amount\":25,\"currency\":\"PEN\"}"));

            replayed(postJson("/credits/" + card.getId() + "/charges",
                    "{\"operationId\":\"" + operationId + "\",\"amount\":25.00,\"currency\":\"PEN\"}"));
        }

        @Test
        void descriptionIsNotComparedAndTheOriginalOneIsReturned() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            UUID operationId = UUID.randomUUID();
            String uri = "/credits/" + card.getId() + "/charges";
            created(post(uri, Map.of("operationId", operationId, "amount", 10, "currency", "PEN", "description", "first")));

            OperationResponse repeated = replayed(post(uri, Map.of("operationId", operationId, "amount", 10,
                    "currency", "PEN", "description", "second")));

            assertThat(repeated.getDescription()).isEqualTo("first");
        }

        @Test
        void sameKeyWithOtherDataIsAConflict() {
            UUID customer = personalCustomer();
            CreditResponse card = openCard(customer, "1000.00");
            CreditResponse otherCard = openCard(customer, "1000.00");
            UUID operationId = UUID.randomUUID();
            created(chargeRequest(card.getId(), operationId, "300.00"));

            assertError(chargeRequest(card.getId(), operationId, "300.01"), 409, "OPERATION_ID_CONFLICT");
            assertError(chargeRequest(card.getId(), operationId, "300.00", "USD"), 409, "OPERATION_ID_CONFLICT");
            assertError(payRequest(card.getId(), operationId, "300.00"), 409, "OPERATION_ID_CONFLICT");
            assertError(chargeRequest(otherCard.getId(), operationId, "300.00"), 409, "OPERATION_ID_CONFLICT");

            assertMoney(storedDebt(card.getId()), "300.00");
            assertMoney(storedDebt(otherCard.getId()), "0");
            assertThat(operationCount()).isEqualTo(1);
        }

        @Test
        void rejectedOperationLeavesTheKeyFree() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            UUID operationId = UUID.randomUUID();

            assertRuleViolation(chargeRequest(card.getId(), operationId, "1000.01"), "INSUFFICIENT_AVAILABLE_CREDIT");
            assertThat(operationCount()).isZero();

            OperationResponse retried = created(chargeRequest(card.getId(), operationId, "1000.00"));
            assertMoney(retried.getAvailableCreditAfter(), "0");
        }
    }

    @Nested
    class Rules {

        @Test
        void chargeNeedsAnActiveCardWithEnoughCreditInItsCurrency() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            CreditResponse loan = openPersonalLoan(personalCustomer(), "500.00");
            charge(card.getId(), "900.00");

            assertRuleViolation(chargeRequest(card.getId(), UUID.randomUUID(), "100.01"), "INSUFFICIENT_AVAILABLE_CREDIT");
            assertRuleViolation(chargeRequest(card.getId(), UUID.randomUUID(), "10.00", "USD"), "CURRENCY_MISMATCH");
            assertRuleViolation(chargeRequest(loan.getId(), UUID.randomUUID(), "10.00"), "NOT_A_CREDIT_CARD");
            assertError(chargeRequest(UUID.randomUUID(), UUID.randomUUID(), "10.00"), 404, "CREDIT_NOT_FOUND");

            assertMoney(storedDebt(card.getId()), "900.00");
            assertMoney(storedDebt(loan.getId()), "500.00");
        }

        @Test
        void paymentCannotExceedTheDebt() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            charge(card.getId(), "50.00");

            assertRuleViolation(payRequest(card.getId(), UUID.randomUUID(), "50.01"), "PAYMENT_EXCEEDS_DEBT");
            assertRuleViolation(payRequest(card.getId(), UUID.randomUUID(), "10.00", "USD"), "CURRENCY_MISMATCH");
            OperationResponse paid = pay(card.getId(), "50.00");

            assertMoney(paid.getOutstandingDebtAfter(), "0");
            assertRuleViolation(payRequest(card.getId(), UUID.randomUUID(), "0.01"), "PAYMENT_EXCEEDS_DEBT");
        }

        @Test
        void closedProductRejectsOperations() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            webTestClient.delete().uri("/credits/{id}", card.getId()).header(HttpHeaders.IF_MATCH, "\"0\"")
                    .exchange().expectStatus().isNoContent().expectBody(Void.class);

            assertError(chargeRequest(card.getId(), UUID.randomUUID(), "10.00"), 409, "CREDIT_CLOSED");
            assertError(payRequest(card.getId(), UUID.randomUUID(), "10.00"), 409, "CREDIT_CLOSED");
            assertThat(operationCount()).isZero();
        }

        @Test
        void contractRejectsMalformedOperations() {
            UUID creditId = openCard(personalCustomer(), "1000.00").getId();
            String uri = "/credits/" + creditId + "/charges";
            UUID key = UUID.randomUUID();

            assertError(postJson(uri, "{\"operationId\":\"" + key + "\",\"amount\":10.001,\"currency\":\"PEN\"}"),
                    400, "INVALID_REQUEST");
            assertError(postJson(uri, "{\"operationId\":\"" + key + "\",\"amount\":0,\"currency\":\"PEN\"}"),
                    400, "INVALID_REQUEST");
            assertError(postJson(uri, "{\"operationId\":\"" + key + "\",\"amount\":-1,\"currency\":\"PEN\"}"),
                    400, "INVALID_REQUEST");
            assertError(postJson(uri, "{\"amount\":10,\"currency\":\"PEN\"}"), 400, "INVALID_REQUEST");
            assertError(postJson(uri, "{\"operationId\":\"" + key + "\",\"amount\":10}"), 400, "INVALID_REQUEST");
            assertError(postJson(uri, "{\"operationId\":\"" + key + "\",\"amount\":10,\"currency\":\"EUR\"}"),
                    400, "INVALID_REQUEST");
            assertError(postJson(uri, "{\"operationId\":\"" + key + "\",\"amount\":10,\"currency\":\"PEN\","
                    + "\"description\":\"" + "x".repeat(141) + "\"}"), 400, "INVALID_REQUEST");
            assertError(postJson("/credits/not-a-uuid/charges",
                    "{\"operationId\":\"" + key + "\",\"amount\":10,\"currency\":\"PEN\"}"), 400, "INVALID_REQUEST");
            assertThat(operationCount()).isZero();
        }

        @Test
        void availableCreditIsForCardsOnly() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "500.00");

            assertRuleViolation(webTestClient.get().uri("/credits/{id}/available-balance", loan.getId()).exchange(),
                    "NOT_A_CREDIT_CARD");
            assertError(webTestClient.get().uri("/credits/{id}/available-balance", UUID.randomUUID()).exchange(),
                    404, "CREDIT_NOT_FOUND");
            assertError(webTestClient.get().uri("/credits/{id}/debt", UUID.randomUUID()).exchange(),
                    404, "CREDIT_NOT_FOUND");
        }
    }
}
