package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.LatestMovementsResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementTotals;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.ProductReportLine;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.ProductReportResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** History of a product, last ten movements of a card and the product report by date range. */
class MovementsAndReportApiTest extends PostgresIntegrationTest {

    @Nested
    class History {

        @Test
        void historyIsPagedNewestFirst() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            List<OperationResponse> operations = new ArrayList<>();
            for (String amount : List.of("10.00", "20.00", "30.00")) {
                clock.advance(Duration.ofMinutes(1));
                operations.add(charge(card.getId(), amount));
            }

            MovementPageResponse first = page(card.getId(), 0, 2);
            MovementPageResponse second = page(card.getId(), 1, 2);

            assertThat(first.getTotalElements()).isEqualTo(3);
            assertThat(first.getTotalPages()).isEqualTo(2);
            assertThat(first.getItems()).extracting(MovementResponse::getOperationId)
                    .containsExactly(operations.get(2).getOperationId(), operations.get(1).getOperationId());
            assertThat(second.getItems()).extracting(MovementResponse::getOperationId)
                    .containsExactly(operations.get(0).getOperationId());
            MovementResponse newest = first.getItems().get(0);
            assertThat(newest.getId()).isEqualTo(operations.get(2).getMovementId());
            assertThat(newest.getCreditId()).isEqualTo(card.getId());
            assertThat(newest.getType().getValue()).isEqualTo("CHARGE");
            assertThat(newest.getCurrency().getValue()).isEqualTo("PEN");
            assertMoney(newest.getAmount(), "30.00");
            assertMoney(newest.getOutstandingDebtAfter(), "60.00");
            assertThat(newest.getOccurredAt().toInstant()).isEqualTo(clock.instant());
        }

        @Test
        void movementsInTheSameMillisecondKeepThePostingOrder() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");

            OperationResponse first = charge(card.getId(), "1.00");
            OperationResponse second = charge(card.getId(), "2.00");
            OperationResponse third = pay(card.getId(), "3.00");

            assertThat(page(card.getId(), 0, 10).getItems()).extracting(MovementResponse::getId)
                    .containsExactly(third.getMovementId(), second.getMovementId(), first.getMovementId());
            assertThat(second.getOccurredAt()).isAfter(first.getOccurredAt());
        }

        @Test
        void historyOfAMissingProductIsNotFound() {
            assertError(webTestClient.get().uri("/credits/{id}/movements", UUID.randomUUID()).exchange(),
                    404, "CREDIT_NOT_FOUND");
            assertError(webTestClient.get().uri("/credits/{id}/movements?size=101", UUID.randomUUID()).exchange(),
                    400, "INVALID_REQUEST");
        }

        @Test
        void latestTenMovementsOfACard() {
            CreditResponse card = openCard(personalCustomer(), "1000.00");
            List<UUID> movementIds = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                clock.advance(Duration.ofSeconds(1));
                movementIds.add(charge(card.getId(), i + ".00").getMovementId());
            }

            LatestMovementsResponse latest = webTestClient.get().uri("/credits/{id}/movements/latest", card.getId())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(LatestMovementsResponse.class).returnResult().getResponseBody();

            List<UUID> newestFirst = new ArrayList<>(movementIds);
            java.util.Collections.reverse(newestFirst);
            assertThat(latest.getCreditId()).isEqualTo(card.getId());
            assertThat(latest.getItems()).extracting(MovementResponse::getId)
                    .containsExactlyElementsOf(newestFirst.subList(0, 10));
        }

        @Test
        void latestMovementsAreForCardsOnly() {
            CreditResponse loan = openPersonalLoan(personalCustomer(), "100.00");

            assertRuleViolation(webTestClient.get().uri("/credits/{id}/movements/latest", loan.getId()).exchange(),
                    "NOT_A_CREDIT_CARD");
            assertError(webTestClient.get().uri("/credits/{id}/movements/latest", UUID.randomUUID()).exchange(),
                    404, "CREDIT_NOT_FOUND");
        }

        @Test
        void newCardHasNoMovements() {
            CreditResponse card = openCard(personalCustomer(), "100.00");

            webTestClient.get().uri("/credits/{id}/movements/latest", card.getId()).exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.items.length()").isEqualTo(0);
            assertThat(page(card.getId(), 0, 20).getTotalPages()).isZero();
        }

        private MovementPageResponse page(UUID creditId, int page, int size) {
            return webTestClient.get().uri("/credits/{id}/movements?page={page}&size={size}", creditId, page, size)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(MovementPageResponse.class).returnResult().getResponseBody();
        }
    }

    @Nested
    class Report {

        /**
         * 16 Nov (Lima): card PEN opened, charge 300, personal loan of 500 opened.
         * 17 Nov: charge 100, loan payment 200, card USD opened, charge 50 at 23:59:59.999.
         * 18 Nov 00:00: card PEN paid off (450) and closed. 19 Nov: charge 50 on the USD card.
         */
        private void history() {
            UUID person = personalCustomer();
            CreditResponse cardPen = openCard(person, "1000.00");
            charge(cardPen.getId(), "300.00");
            CreditResponse loan = openPersonalLoan(person, "500.00");

            clock.set(Instant.parse("2026-11-17T15:00:00Z"));
            charge(cardPen.getId(), "100.00");
            pay(loan.getId(), "200.00");
            CreditResponse cardUsd = open(openRequest(person, "CREDIT_CARD", "USD", null, "500.00"));
            clock.set(Instant.parse("2026-11-18T04:59:59.999Z"));
            charge(cardPen.getId(), "50.00");

            clock.set(Instant.parse("2026-11-18T05:00:00Z"));
            pay(cardPen.getId(), "450.00");
            webTestClient.delete().uri("/credits/{id}", cardPen.getId())
                    .header(HttpHeaders.IF_MATCH, etag(cardPen.getId()))
                    .exchange().expectStatus().isNoContent().expectBody(Void.class);

            clock.set(Instant.parse("2026-11-19T15:00:00Z"));
            created(chargeRequest(cardUsd.getId(), UUID.randomUUID(), "50.00", "USD"));
        }

        @Test
        void firstTwoDays() {
            history();

            ProductReportResponse report = report("2026-11-16", "2026-11-17");

            assertThat(report.getFrom()).hasToString("2026-11-16");
            assertThat(report.getTo()).hasToString("2026-11-17");
            assertThat(report.getBusinessZone()).isEqualTo("America/Lima");
            assertThat(report.getGeneratedAt().toInstant()).isEqualTo(clock.instant());
            assertThat(report.getProducts()).hasSize(6).allSatisfy(line -> assertThat(line.getMovements()).hasSize(3));

            ProductReportLine cardPen = line(report, "CREDIT_CARD", "PEN");
            assertCounts(cardPen, 1, 0, 1, "450.00");
            assertMovements(cardPen, "DISBURSEMENT", 0, "0");
            assertMovements(cardPen, "CHARGE", 3, "450.00");
            assertMovements(cardPen, "PAYMENT", 0, "0");

            ProductReportLine cardUsd = line(report, "CREDIT_CARD", "USD");
            assertCounts(cardUsd, 1, 0, 1, "0");
            assertMovements(cardUsd, "CHARGE", 0, "0");

            ProductReportLine loan = line(report, "PERSONAL_LOAN", "PEN");
            assertCounts(loan, 1, 0, 1, "300.00");
            assertMovements(loan, "DISBURSEMENT", 1, "500.00");
            assertMovements(loan, "PAYMENT", 1, "200.00");

            assertCounts(line(report, "BUSINESS_LOAN", "PEN"), 0, 0, 0, "0");
            assertCounts(line(report, "PERSONAL_LOAN", "USD"), 0, 0, 0, "0");
        }

        @Test
        void lastTwoDays() {
            history();

            ProductReportResponse report = report("2026-11-18", "2026-11-19");

            ProductReportLine cardPen = line(report, "CREDIT_CARD", "PEN");
            assertCounts(cardPen, 0, 1, 0, "0");
            assertMovements(cardPen, "PAYMENT", 1, "450.00");
            assertMovements(cardPen, "CHARGE", 0, "0");

            ProductReportLine cardUsd = line(report, "CREDIT_CARD", "USD");
            assertCounts(cardUsd, 0, 0, 1, "50.00");
            assertMovements(cardUsd, "CHARGE", 1, "50.00");

            ProductReportLine loan = line(report, "PERSONAL_LOAN", "PEN");
            assertCounts(loan, 0, 0, 1, "300.00");
            assertMovements(loan, "PAYMENT", 0, "0");
        }

        @Test
        void oneDayUsesTheDebtAtItsEnd() {
            history();

            ProductReportResponse report = report("2026-11-16", "2026-11-16");

            assertCounts(line(report, "CREDIT_CARD", "PEN"), 1, 0, 1, "300.00");
            assertCounts(line(report, "PERSONAL_LOAN", "PEN"), 1, 0, 1, "500.00");
            assertCounts(line(report, "CREDIT_CARD", "USD"), 0, 0, 0, "0");
        }

        @Test
        void rangeLimits() {
            webTestClient.get().uri("/credits/reports/products?from=2026-01-01&to=2027-01-01").exchange()
                    .expectStatus().isOk().expectBody(Void.class);
            assertError(webTestClient.get().uri("/credits/reports/products?from=2026-01-01&to=2027-01-02").exchange(),
                    400, "INVALID_REQUEST");
            assertError(webTestClient.get().uri("/credits/reports/products?from=2026-11-17&to=2026-11-16").exchange(),
                    400, "INVALID_REQUEST");
            assertError(webTestClient.get().uri("/credits/reports/products?from=2026-11-16").exchange(),
                    400, "INVALID_REQUEST");
            assertError(webTestClient.get().uri("/credits/reports/products?from=16-11-2026&to=2026-11-17").exchange(),
                    400, "INVALID_REQUEST");
        }

        private ProductReportResponse report(String from, String to) {
            return webTestClient.get().uri("/credits/reports/products?from={from}&to={to}", from, to).exchange()
                    .expectStatus().isOk()
                    .expectBody(ProductReportResponse.class).returnResult().getResponseBody();
        }

        private static ProductReportLine line(ProductReportResponse report, String type, String currency) {
            return report.getProducts().stream()
                    .filter(line -> line.getType().getValue().equals(type) && line.getCurrency().getValue().equals(currency))
                    .findFirst()
                    .orElseThrow();
        }

        private static void assertCounts(ProductReportLine line, long opened, long closed, long active, String debt) {
            assertThat(line.getCreditsOpened()).as("opened").isEqualTo(opened);
            assertThat(line.getCreditsClosed()).as("closed").isEqualTo(closed);
            assertThat(line.getActiveCreditsAtEnd()).as("active at end").isEqualTo(active);
            assertMoney(line.getOutstandingDebtAtEnd(), debt);
        }

        private static void assertMovements(ProductReportLine line, String type, long count, String total) {
            MovementTotals totals = line.getMovements().stream()
                    .filter(movement -> movement.getType().getValue().equals(type))
                    .findFirst()
                    .orElseThrow();
            assertThat(totals.getCount()).as(type + " count").isEqualTo(count);
            assertMoney(totals.getTotalAmount(), total);
        }
    }
}
