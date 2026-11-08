package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /credits as Account Service calls it to find an active credit card:
 * {@code ?customerId=&type=CREDIT_CARD&status=ACTIVE&size=1}, without page. Answered from
 * credits_db only; Customer Service is never called.
 */
class ListCreditsApiTest extends PostgresIntegrationTest {

    private static final String ACCOUNT_SERVICE_QUERY = "/credits?customerId={id}&type=CREDIT_CARD&status=ACTIVE&size=1";

    @Test
    void activeCardIsFound() {
        UUID customer = personalCustomer();
        CreditResponse card = openCard(customer, "1000.00");

        webTestClient.get().uri(ACCOUNT_SERVICE_QUERY, customer).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(card.getId().toString())
                .jsonPath("$.items[0].type").isEqualTo("CREDIT_CARD")
                .jsonPath("$.items[0].status").isEqualTo("ACTIVE")
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(1)
                .jsonPath("$.totalElements").isEqualTo(1)
                .jsonPath("$.totalPages").isEqualTo(1);
    }

    @Test
    void totalCountsEveryMatchRegardlessOfSize() {
        UUID customer = personalCustomer();
        openCard(customer, "1000.00");
        clock.advance(Duration.ofMinutes(1));
        openCard(customer, "2000.00");
        clock.advance(Duration.ofMinutes(1));
        openCard(customer, "3000.00");

        CreditPageResponse page = query(customer);

        assertThat(page.getItems()).hasSize(1);
        assertMoney(page.getItems().get(0).getCreditLimit(), "1000.00");
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void closedCardIsNotCounted() {
        UUID customer = personalCustomer();
        CreditResponse card = openCard(customer, "1000.00");
        webTestClient.delete().uri("/credits/{id}", card.getId()).header(HttpHeaders.IF_MATCH, "\"0\"")
                .exchange().expectStatus().isNoContent().expectBody(Void.class);

        assertEmpty(query(customer));
    }

    @Test
    void activeLoanIsNotACard() {
        UUID customer = personalCustomer();
        openPersonalLoan(customer, "5000.00");

        assertEmpty(query(customer));
    }

    @Test
    void cardOfAnotherCustomerIsNotCounted() {
        UUID customer = personalCustomer();
        openCard(personalCustomer(), "1000.00");

        assertEmpty(query(customer));
    }

    @Test
    void customerWithoutProductsGetsAnEmptyPage() {
        assertEmpty(query(UUID.randomUUID()));
    }

    @Test
    void answersWithoutCustomerService() {
        UUID customer = personalCustomer();
        openCard(customer, "1000.00");
        CUSTOMERS.fail(503);
        int callsBefore = CUSTOMERS.calls();

        assertThat(query(customer).getTotalElements()).isEqualTo(1);
        assertThat(CUSTOMERS.calls()).isEqualTo(callsBefore);
    }

    @Test
    void filtersAreOptionalAndPagesAreOrderedByOpening() {
        UUID customer = personalCustomer();
        CreditResponse loan = openPersonalLoan(customer, "500.00");
        clock.advance(Duration.ofMinutes(1));
        CreditResponse card = openCard(customer, "1000.00");
        openCard(personalCustomer(), "1000.00");

        CreditPageResponse mine = webTestClient.get().uri("/credits?customerId={id}", customer).exchange()
                .expectStatus().isOk()
                .expectBody(CreditPageResponse.class).returnResult().getResponseBody();
        assertThat(mine.getItems()).extracting(CreditResponse::getId).containsExactly(loan.getId(), card.getId());
        assertThat(mine.getSize()).isEqualTo(20);

        CreditPageResponse all = webTestClient.get().uri("/credits?page=1&size=2").exchange()
                .expectStatus().isOk()
                .expectBody(CreditPageResponse.class).returnResult().getResponseBody();
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getTotalPages()).isEqualTo(2);
        assertThat(all.getItems()).hasSize(1);
    }

    @Test
    void invalidFiltersAreRejected() {
        assertError(webTestClient.get().uri("/credits?type=MORTGAGE").exchange(), 400, "INVALID_REQUEST");
        assertError(webTestClient.get().uri("/credits?customerId=not-a-uuid").exchange(), 400, "INVALID_REQUEST");
        assertError(webTestClient.get().uri("/credits?size=0").exchange(), 400, "INVALID_REQUEST");
        assertError(webTestClient.get().uri("/credits?size=101").exchange(), 400, "INVALID_REQUEST");
    }

    private CreditPageResponse query(UUID customer) {
        return webTestClient.get().uri(ACCOUNT_SERVICE_QUERY, customer).exchange()
                .expectStatus().isOk()
                .expectBody(CreditPageResponse.class).returnResult().getResponseBody();
    }

    private static void assertEmpty(CreditPageResponse page) {
        assertThat(page.getItems()).isEmpty();
        assertThat(page.getPage()).isZero();
        assertThat(page.getSize()).isEqualTo(1);
        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getTotalPages()).isZero();
    }
}
