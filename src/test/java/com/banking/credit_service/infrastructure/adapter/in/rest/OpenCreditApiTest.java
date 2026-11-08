package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opening a product: rules of the product, eligibility of the customer according to Customer
 * Service (a stub), and what happens when Customer Service fails.
 */
class OpenCreditApiTest extends PostgresIntegrationTest {

    @Nested
    class Products {

        @Test
        void opensACardWithoutDebt() {
            UUID customer = personalCustomer();

            CreditResponse card = openRequest(customer, "CREDIT_CARD", "PEN", null, "1000.00")
                    .expectStatus().isCreated()
                    .expectHeader().valueMatches(HttpHeaders.LOCATION, "/credits/[0-9a-f-]{36}")
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"0\"")
                    .expectBody(CreditResponse.class).returnResult().getResponseBody();

            assertThat(card.getCustomerId()).isEqualTo(customer);
            assertThat(card.getCustomerType().getValue()).isEqualTo("PERSONAL");
            assertThat(card.getType().getValue()).isEqualTo("CREDIT_CARD");
            assertThat(card.getCurrency().getValue()).isEqualTo("PEN");
            assertThat(card.getStatus().getValue()).isEqualTo("ACTIVE");
            assertMoney(card.getCreditLimit(), "1000.00");
            assertMoney(card.getOutstandingDebt(), "0");
            assertMoney(card.getAvailableCredit(), "1000.00");
            assertThat(card.getPaymentDueDay()).isEqualTo(15);
            assertThat(card.getOpenedAt().toInstant()).isEqualTo(clock.instant());
            assertThat(CUSTOMERS.calls()).isEqualTo(1);
            assertThat(movementCount(card.getId())).as("a card starts without movements").isZero();

            String json = webTestClient.get().uri("/credits/{id}", card.getId()).exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ETAG, "\"0\"")
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(json).as("fields that do not apply are absent, not null")
                    .doesNotContain("\"principal\"").doesNotContain("\"closedAt\"").doesNotContain("null");
        }

        @Test
        void opensAPersonalLoanOwingItsPrincipal() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "5000.00");

            assertMoney(loan.getPrincipal(), "5000.00");
            assertMoney(loan.getOutstandingDebt(), "5000.00");
            assertThat(loan.getCreditLimit()).isNull();
            assertThat(loan.getAvailableCredit()).isNull();

            MovementPageResponse history = movements(loan.getId());
            assertThat(history.getTotalElements()).isEqualTo(1);
            MovementResponse disbursement = history.getItems().get(0);
            assertThat(disbursement.getType().getValue()).isEqualTo("DISBURSEMENT");
            assertMoney(disbursement.getAmount(), "5000.00");
            assertMoney(disbursement.getOutstandingDebtAfter(), "5000.00");
            assertThat(disbursement.getOperationId()).isNotNull();
            assertMoney(debt(loan.getId()).getOutstandingDebt(), "5000.00");
        }

        @Test
        void businessCustomerHoldsBusinessLoansAndCards() {
            UUID company = businessCustomer();

            CreditResponse first = openBusinessLoan(company, "20000.00");
            CreditResponse second = openBusinessLoan(company, "15000.00");
            CreditResponse card = openCard(company, "8000.00");

            assertThat(first.getCustomerType().getValue()).isEqualTo("BUSINESS");
            assertThat(second.getId()).isNotEqualTo(first.getId());
            assertThat(card.getType().getValue()).isEqualTo("CREDIT_CARD");
            assertThat(creditCount()).isEqualTo(3);
        }

        @Test
        void personalCustomerCanHoldSeveralCards() {
            UUID customer = personalCustomer();

            openCard(customer, "1000.00");
            open(openRequest(customer, "CREDIT_CARD", "USD", null, "500.00"));

            assertThat(creditCount()).isEqualTo(2);
        }

        @Test
        void secondActivePersonalLoanIsRejectedUntilTheFirstIsClosed() {
            UUID customer = personalCustomer();
            CreditResponse first = openPersonalLoan(customer, "300.00");

            assertError(openRequest(customer, "PERSONAL_LOAN", "PEN", "100.00", null), 409, "PERSONAL_LOAN_ALREADY_EXISTS");
            assertThat(creditCount()).isEqualTo(1);

            pay(first.getId(), "300.00");
            webTestClient.delete().uri("/credits/{id}", first.getId()).header(HttpHeaders.IF_MATCH, etag(first.getId()))
                    .exchange().expectStatus().isNoContent().expectBody(Void.class);

            CreditResponse second = openPersonalLoan(customer, "100.00");
            assertThat(second.getId()).isNotEqualTo(first.getId());
        }
    }

    @Nested
    class Eligibility {

        @Test
        void unknownCustomerIsRejected() {
            assertRuleViolation(openRequest(UUID.randomUUID(), "CREDIT_CARD", "PEN", null, "100.00"), "CUSTOMER_NOT_FOUND");
            assertThat(creditCount()).isZero();
        }

        @Test
        void inactiveCustomerIsRejected() {
            UUID inactive = CUSTOMERS.addCustomer("PERSONAL", false);

            assertRuleViolation(openRequest(inactive, "CREDIT_CARD", "PEN", null, "100.00"), "CUSTOMER_INACTIVE");
            assertThat(creditCount()).isZero();
        }

        @Test
        void loanMustMatchTheCustomerType() {
            assertRuleViolation(openRequest(businessCustomer(), "PERSONAL_LOAN", "PEN", "100.00", null), "PRODUCT_NOT_ALLOWED");
            assertRuleViolation(openRequest(personalCustomer(), "BUSINESS_LOAN", "PEN", "100.00", null), "PRODUCT_NOT_ALLOWED");
            assertThat(creditCount()).isZero();
        }

        @Test
        void termsAreCheckedBeforeCallingCustomerService() {
            UUID customer = personalCustomer();

            assertRuleViolation(openRequest(customer, "CREDIT_CARD", "PEN", null, null), "INVALID_PRODUCT_TERMS");
            assertRuleViolation(openRequest(customer, "CREDIT_CARD", "PEN", "100.00", "100.00"), "INVALID_PRODUCT_TERMS");
            assertRuleViolation(openRequest(customer, "PERSONAL_LOAN", "PEN", null, null), "INVALID_PRODUCT_TERMS");
            assertRuleViolation(openRequest(customer, "PERSONAL_LOAN", "PEN", "100.00", "50.00"), "INVALID_PRODUCT_TERMS");

            assertThat(CUSTOMERS.calls()).isZero();
            assertThat(creditCount()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"100.001", "0", "-5", "1000000000000"})
        void invalidAmountsAreRejectedByTheContract(String amount) {
            String json = "{\"customerId\":\"" + personalCustomer() + "\",\"type\":\"CREDIT_CARD\",\"currency\":\"PEN\","
                    + "\"creditLimit\":" + amount + ",\"paymentDueDay\":15}";

            assertError(postJson("/credits", json), 400, "INVALID_REQUEST");
            assertThat(CUSTOMERS.calls()).isZero();
        }

        @Test
        void malformedRequestsAreRejected() {
            UUID customer = personalCustomer();
            assertError(postJson("/credits", "{\"customerId\":\"" + customer + "\",\"type\":\"CREDIT_CARD\","
                    + "\"currency\":\"PEN\",\"creditLimit\":100,\"paymentDueDay\":29}"), 400, "INVALID_REQUEST");
            assertError(postJson("/credits", "{\"customerId\":\"" + customer + "\",\"type\":\"MORTGAGE\","
                    + "\"currency\":\"PEN\",\"principal\":100,\"paymentDueDay\":5}"), 400, "INVALID_REQUEST");
            assertError(postJson("/credits", "{\"customerId\":\"" + customer + "\",\"type\":\"CREDIT_CARD\","
                    + "\"currency\":\"PEN\",\"creditLimit\":100,\"paymentDueDay\":5,\"rate\":3}"), 400, "INVALID_REQUEST");
            assertError(webTestClient.post().uri("/credits").header(HttpHeaders.CONTENT_TYPE, "text/plain")
                    .bodyValue("x").exchange(), 415, "UNSUPPORTED_MEDIA_TYPE");
            assertThat(CUSTOMERS.calls()).isZero();
        }
    }

    @Nested
    class CustomerServiceFailures {

        @ParameterizedTest
        @ValueSource(ints = {500, 502, 503})
        void serverErrorIsUnavailable(int status) {
            UUID customer = personalCustomer();
            CUSTOMERS.fail(status);

            assertUnavailable(openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"));
        }

        @Test
        void notFoundWithoutTheCustomerCodeIsAMisroute() {
            UUID customer = personalCustomer();
            CUSTOMERS.misroute();

            assertUnavailable(openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"));
        }

        @Test
        void answerOutsideTheContractIsUnavailable() {
            UUID customer = personalCustomer();
            CUSTOMERS.answer("{\"id\":\"" + customer + "\",\"type\":\"PERSONAL\",\"status\":\"BLOCKED\"}");

            assertUnavailable(openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"));
        }

        @Test
        void slowCustomerServiceTimesOutAfterTwoSeconds() {
            UUID customer = personalCustomer();
            CUSTOMERS.delay(Duration.ofSeconds(5));

            long start = System.nanoTime();
            assertUnavailable(openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1900)).isLessThan(Duration.ofSeconds(4));
            assertThat(customerCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        }

        @Test
        void openCircuitAnswersWithoutCallingCustomerService() {
            UUID customer = personalCustomer();
            CUSTOMERS.fail(500);
            for (int attempt = 0; attempt < 5; attempt++) {
                assertUnavailable(openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"));
            }
            assertThat(customerCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            CUSTOMERS.reset();
            UUID healthy = personalCustomer();
            assertUnavailable(openRequest(healthy, "CREDIT_CARD", "PEN", null, "100.00"));

            assertThat(CUSTOMERS.calls()).as("the open circuit does not call").isZero();
            assertThat(customerCircuitBreaker().getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
        }

        @Test
        void businessRejectionsDoNotCountAsFailures() {
            UUID inactive = CUSTOMERS.addCustomer("PERSONAL", false);
            UUID company = businessCustomer();
            for (int attempt = 0; attempt < 4; attempt++) {
                assertRuleViolation(openRequest(UUID.randomUUID(), "CREDIT_CARD", "PEN", null, "100.00"), "CUSTOMER_NOT_FOUND");
                assertRuleViolation(openRequest(inactive, "CREDIT_CARD", "PEN", null, "100.00"), "CUSTOMER_INACTIVE");
                assertRuleViolation(openRequest(company, "PERSONAL_LOAN", "PEN", "100.00", null), "PRODUCT_NOT_ALLOWED");
            }

            CircuitBreaker breaker = customerCircuitBreaker();
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(10);
            assertThat(CUSTOMERS.calls()).isEqualTo(12);
            openCard(personalCustomer(), "100.00");
        }

        private void assertUnavailable(org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec response) {
            response.expectStatus().isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
                    .jsonPath("$.message").isEqualTo("Customer Service is unavailable. Nothing was written; retry later.");
            assertThat(creditCount()).as("nothing written").isZero();
        }
    }
}
