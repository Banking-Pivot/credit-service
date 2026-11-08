package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Administrative update of the terms and logical closing, both guarded by If-Match. */
class UpdateAndCloseCreditApiTest extends PostgresIntegrationTest {

    @Nested
    class Update {

        @Test
        void changesDueDayAndLimitButNotTheDebt() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            charge(card.getId(), "300.00");

            CreditResponse updated = update(card.getId(), "\"1\"", "{\"paymentDueDay\":5,\"creditLimit\":1500}")
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"2\"")
                    .expectBody(CreditResponse.class).returnResult().getResponseBody();

            assertThat(updated.getPaymentDueDay()).isEqualTo(5);
            assertMoney(updated.getCreditLimit(), "1500.00");
            assertMoney(updated.getOutstandingDebt(), "300.00");
            assertMoney(updated.getAvailableCredit(), "1200.00");
            assertMoney(available(card.getId()).getAvailableCredit(), "1200.00");
            assertThat(movementCount(card.getId())).as("no movement for a change of terms").isEqualTo(1);
        }

        @Test
        void currentTermsKeepTheRevision() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "500.00");

            update(loan.getId(), "\"0\"", "{\"paymentDueDay\":15}")
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"0\"")
                    .expectBody(Void.class);
        }

        @Test
        void limitMustCoverTheDebt() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            charge(card.getId(), "400.00");

            assertRuleViolation(update(card.getId(), "\"1\"", "{\"paymentDueDay\":15,\"creditLimit\":399.99}"),
                    "CREDIT_LIMIT_BELOW_DEBT");

            update(card.getId(), "\"1\"", "{\"paymentDueDay\":15,\"creditLimit\":400}")
                    .expectStatus().isOk().expectBody(Void.class);
            assertMoney(available(card.getId()).getAvailableCredit(), "0");
            assertRuleViolation(chargeRequest(card.getId(), UUID.randomUUID(), "0.01"), "INSUFFICIENT_AVAILABLE_CREDIT");
        }

        @Test
        void limitBelongsToCards() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "500.00");
            CreditResponse card = openCard(personalCustomer(), "1000.00");

            assertRuleViolation(update(loan.getId(), "\"0\"", "{\"paymentDueDay\":10,\"creditLimit\":100}"),
                    "INVALID_PRODUCT_TERMS");
            assertRuleViolation(update(card.getId(), "\"0\"", "{\"paymentDueDay\":10}"), "INVALID_PRODUCT_TERMS");
        }

        @Test
        void debtIsNotEditable() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");

            assertError(update(card.getId(), "\"0\"", "{\"paymentDueDay\":10,\"creditLimit\":100,\"outstandingDebt\":0}"),
                    400, "INVALID_REQUEST");
            assertError(update(card.getId(), "\"0\"", "{\"paymentDueDay\":0,\"creditLimit\":100}"), 400, "INVALID_REQUEST");
        }

        @Test
        void ifMatchGuardsTheRevision() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            String body = "{\"paymentDueDay\":10,\"creditLimit\":2000}";
            charge(card.getId(), "1.00");

            assertError(update(card.getId(), "\"0\"", body), 412, "CREDIT_VERSION_CONFLICT");
            assertError(update(card.getId(), "\"01\"", body), 412, "CREDIT_VERSION_CONFLICT");
            // Beyond the long range but within maxLength 21: well formed, never issued.
            assertError(update(card.getId(), "\"9999999999999999999\"", body), 412, "CREDIT_VERSION_CONFLICT");
            assertError(update(card.getId(), "W/\"1\"", body), 400, "INVALID_IF_MATCH");
            assertError(update(card.getId(), "*", body), 400, "INVALID_IF_MATCH");
            assertError(update(card.getId(), null, body), 428, "IF_MATCH_REQUIRED");
            assertError(update(UUID.randomUUID(), "\"0\"", body), 404, "CREDIT_NOT_FOUND");
            assertError(update(UUID.randomUUID(), "\"01\"", body), 404, "CREDIT_NOT_FOUND");

            assertThat(etag(card.getId())).isEqualTo("\"1\"");
        }

        @Test
        void closedProductCannotBeUpdated() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            close(card.getId(), "\"0\"").expectStatus().isNoContent().expectBody(Void.class);

            assertError(update(card.getId(), "\"1\"", "{\"paymentDueDay\":10,\"creditLimit\":100}"), 409, "CREDIT_CLOSED");
        }
    }

    @Nested
    class Close {

        @Test
        void closingNeedsZeroDebtAndKeepsTheHistory() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "300.00");

            assertRuleViolation(close(loan.getId(), "\"0\""), "DEBT_NOT_ZERO");
            pay(loan.getId(), "300.00");
            clock.advance(java.time.Duration.ofHours(1));
            close(loan.getId(), "\"1\"").expectStatus().isNoContent().expectBody(Void.class);

            CreditResponse closed = credit(loan.getId());
            assertThat(closed.getStatus().getValue()).isEqualTo("CLOSED");
            assertThat(closed.getClosedAt().toInstant()).isEqualTo(clock.instant());
            assertMoney(closed.getOutstandingDebt(), "0");
            assertThat(etag(loan.getId())).isEqualTo("\"2\"");
            assertThat(movements(loan.getId()).getTotalElements()).as("history kept").isEqualTo(2);
        }

        @Test
        void closingAgainWithTheCurrentRevisionChangesNothing() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            close(card.getId(), "\"0\"").expectStatus().isNoContent().expectBody(Void.class);

            close(card.getId(), "\"1\"").expectStatus().isNoContent().expectBody(Void.class);
            assertError(close(card.getId(), "\"0\""), 412, "CREDIT_VERSION_CONFLICT");
            assertThat(etag(card.getId())).isEqualTo("\"1\"");
        }

        @Test
        void closingIsGuardedLikeUpdates() {
            assertError(close(UUID.randomUUID(), "\"0\""), 404, "CREDIT_NOT_FOUND");
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            assertError(close(card.getId(), null), 428, "IF_MATCH_REQUIRED");
            assertError(close(card.getId(), "\"3\""), 412, "CREDIT_VERSION_CONFLICT");
            assertError(close(card.getId(), "abc"), 400, "INVALID_IF_MATCH");
        }
    }

    private WebTestClient.ResponseSpec update(UUID creditId, String ifMatch, String json) {
        WebTestClient.RequestBodySpec request = webTestClient.put().uri("/credits/{id}", creditId)
                .contentType(MediaType.APPLICATION_JSON);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.bodyValue(json).exchange();
    }

    private WebTestClient.ResponseSpec close(UUID creditId, String ifMatch) {
        WebTestClient.RequestHeadersSpec<?> request = webTestClient.delete().uri("/credits/{id}", creditId);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }
}
