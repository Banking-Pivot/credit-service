package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races against the real PostgreSQL: requests are released together by a barrier and the
 * outcome is checked in the responses and in the tables (debt and movement count). The
 * ledger check of the base class runs after each test.
 */
class CreditConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int PARALLEL = 8;

    @RepeatedTest(3)
    void simultaneousPersonalLoanOpeningsCreateOneLoan() throws Exception {
        UUID customer = personalCustomer();

        List<Reply> replies = concurrently(PARALLEL,
                i -> send(openRequest(customer, "PERSONAL_LOAN", "PEN", "1000.00", null)));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(409, (long) PARALLEL - 1);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .allSatisfy(reply -> assertThat(reply.body()).contains("PERSONAL_LOAN_ALREADY_EXISTS"));
        assertThat(creditCount()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM credit_movements", Map.of())).as("one disbursement").isEqualTo(1);
    }

    @RepeatedTest(3)
    void twoChargesThatTogetherExceedTheLimit() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");

        List<Reply> replies = concurrently(2, i -> send(chargeRequest(card.getId(), UUID.randomUUID(), "600.00")));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(422, 1L);
        assertThat(replies).filteredOn(reply -> reply.status() == 422)
                .allSatisfy(reply -> assertThat(reply.body()).contains("INSUFFICIENT_AVAILABLE_CREDIT"));
        assertMoney(storedDebt(card.getId()), "600.00");
        assertThat(movementCount(card.getId())).isEqualTo(1);
    }

    @Test
    void manyChargesNeverExceedTheLimit() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");

        List<Reply> replies = concurrently(10, i -> send(chargeRequest(card.getId(), UUID.randomUUID(), "300.00")));

        assertThat(statuses(replies)).containsEntry(201, 3L).containsEntry(422, 7L);
        assertMoney(storedDebt(card.getId()), "900.00");
        assertThat(movementCount(card.getId())).isEqualTo(3);
    }

    @RepeatedTest(3)
    void twoPaymentsThatTogetherExceedTheDebt() throws Exception {
        CreditResponse loan = openPersonalLoan(personalCustomer(), "500.00");

        List<Reply> replies = concurrently(2, i -> send(payRequest(loan.getId(), UUID.randomUUID(), "300.00")));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(422, 1L);
        assertThat(replies).filteredOn(reply -> reply.status() == 422)
                .allSatisfy(reply -> assertThat(reply.body()).contains("PAYMENT_EXCEEDS_DEBT"));
        assertMoney(storedDebt(loan.getId()), "200.00");
        assertThat(movementCount(loan.getId())).as("disbursement and one payment").isEqualTo(2);
    }

    @Test
    void interleavedChargesAndPaymentsAreAllApplied() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");
        charge(card.getId(), "500.00");

        List<Reply> replies = concurrently(20, i -> i % 2 == 0
                ? send(chargeRequest(card.getId(), UUID.randomUUID(), "20.00"))
                : send(payRequest(card.getId(), UUID.randomUUID(), "20.00")));

        assertThat(statuses(replies)).containsOnlyKeys(201);
        assertMoney(storedDebt(card.getId()), "500.00");
        assertThat(movementCount(card.getId())).isEqualTo(21);
        assertThat(count("SELECT version FROM credits WHERE id = :id", Map.of("id", card.getId()))).isEqualTo(21);
    }

    @RepeatedTest(3)
    void concurrentRepeatsOfAChargeApplyItOnce() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");
        UUID operationId = UUID.randomUUID();

        List<Reply> replies = concurrently(PARALLEL, i -> send(chargeRequest(card.getId(), operationId, "250.00")));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(200, (long) PARALLEL - 1);
        assertThat(replies).filteredOn(reply -> reply.status() == 200)
                .allSatisfy(reply -> assertThat(reply.replayed()).isTrue());
        assertThat(replies.stream().map(Reply::body).distinct()).as("same original result").hasSize(1);
        assertMoney(storedDebt(card.getId()), "250.00");
        assertThat(movementCount(card.getId())).isEqualTo(1);
        assertThat(operationCount()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentRepeatsOfAPaymentPayOnce() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");
        charge(card.getId(), "300.00");
        UUID operationId = UUID.randomUUID();

        List<Reply> replies = concurrently(PARALLEL, i -> send(payRequest(card.getId(), operationId, "100.00")));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(200, (long) PARALLEL - 1);
        assertMoney(storedDebt(card.getId()), "200.00");
        assertThat(movementCount(card.getId())).isEqualTo(2);
    }

    @RepeatedTest(3)
    void concurrentUseOfOneKeyWithDifferentAmounts() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");
        UUID operationId = UUID.randomUUID();

        List<Reply> replies = concurrently(2,
                i -> send(chargeRequest(card.getId(), operationId, i == 0 ? "10.00" : "20.00")));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(409, 1L);
        assertThat(replies).filteredOn(reply -> reply.status() == 409)
                .allSatisfy(reply -> assertThat(reply.body()).contains("OPERATION_ID_CONFLICT"));
        assertThat(storedDebt(card.getId())).isIn(new BigDecimal("10.00"), new BigDecimal("20.00"));
        assertThat(movementCount(card.getId())).isEqualTo(1);
    }

    @RepeatedTest(3)
    void concurrentUseOfOneKeyOnTwoProducts() throws Exception {
        UUID customer = personalCustomer();
        CreditResponse first = openCard(customer, "1000.00");
        CreditResponse second = openCard(customer, "1000.00");
        UUID operationId = UUID.randomUUID();

        List<Reply> replies = concurrently(2,
                i -> send(chargeRequest(i == 0 ? first.getId() : second.getId(), operationId, "10.00")));

        assertThat(statuses(replies)).containsEntry(201, 1L).containsEntry(409, 1L);
        assertThat(storedDebt(first.getId()).add(storedDebt(second.getId()))).isEqualByComparingTo("10.00");
        assertThat(operationCount()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void closeRacingWithAChargeNeverLeavesDebtOnAClosedCard() throws Exception {
        CreditResponse card = openCard(personalCustomer(), "1000.00");

        List<Reply> replies = concurrently(2, i -> i == 0
                ? send(webTestClient.delete().uri("/credits/{id}", card.getId()).header(HttpHeaders.IF_MATCH, "\"0\"")
                .exchange())
                : send(chargeRequest(card.getId(), UUID.randomUUID(), "10.00")));

        Map<Integer, Long> statuses = statuses(replies);
        if (statuses.containsKey(204)) {
            // Closed first: the charge found it closed.
            assertThat(statuses).containsEntry(409, 1L);
            assertMoney(storedDebt(card.getId()), "0");
            assertThat(movementCount(card.getId())).isZero();
        } else {
            // Charged first: the revision changed, so the close failed its precondition.
            assertThat(statuses).containsEntry(201, 1L).containsEntry(412, 1L);
            assertMoney(storedDebt(card.getId()), "10.00");
            assertThat(credit(card.getId()).getStatus().getValue()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void failureBetweenTheDebtUpdateAndTheMovementRollsBackEverything() {
        CreditResponse card = openCard(personalCustomer(), "1000.00");
        charge(card.getId(), "100.00");
        UUID operationId = UUID.randomUUID();
        // The movement is written after the new debt and the claim of the key, in the same transaction.
        execute("CREATE FUNCTION fail_charge_movement() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'simulated failure before the movement'; END $$");
        execute("CREATE TRIGGER fail_charge_movement BEFORE INSERT ON credit_movements FOR EACH ROW "
                + "WHEN (NEW.movement_type = 'CHARGE') EXECUTE FUNCTION fail_charge_movement()");
        try {
            assertError(chargeRequest(card.getId(), operationId, "300.00"), 500, "INTERNAL_ERROR");
        } finally {
            execute("DROP TRIGGER fail_charge_movement ON credit_movements");
            execute("DROP FUNCTION fail_charge_movement()");
        }

        assertMoney(storedDebt(card.getId()), "100.00");
        assertThat(count("SELECT version FROM credits WHERE id = :id", Map.of("id", card.getId())))
                .as("revision untouched").isEqualTo(1);
        assertThat(movementCount(card.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM credit_operations WHERE operation_id = :id",
                Map.of("id", operationId))).as("key not claimed").isZero();

        OperationResponse retried = created(chargeRequest(card.getId(), operationId, "300.00"));

        assertMoney(retried.getOutstandingDebtAfter(), "400.00");
        assertMoney(storedDebt(card.getId()), "400.00");
        assertThat(movementCount(card.getId())).isEqualTo(2);
    }

    @Test
    void failureWhileStoringTheResultRollsBackTheMovementToo() {
        CreditResponse loan = openPersonalLoan(personalCustomer(), "500.00");
        UUID operationId = UUID.randomUUID();
        // Completing the operation is the last write: the debt and the movement are already in.
        execute("CREATE FUNCTION fail_operation_result() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'simulated failure storing the result'; END $$");
        execute("CREATE TRIGGER fail_operation_result BEFORE UPDATE ON credit_operations FOR EACH ROW "
                + "EXECUTE FUNCTION fail_operation_result()");
        try {
            assertError(payRequest(loan.getId(), operationId, "200.00"), 500, "INTERNAL_ERROR");
        } finally {
            execute("DROP TRIGGER fail_operation_result ON credit_operations");
            execute("DROP FUNCTION fail_operation_result()");
        }

        assertMoney(storedDebt(loan.getId()), "500.00");
        assertThat(movementCount(loan.getId())).as("only the disbursement").isEqualTo(1);
        assertThat(operationCount()).isZero();

        assertMoney(created(payRequest(loan.getId(), operationId, "200.00")).getOutstandingDebtAfter(), "300.00");
    }

    private void execute(String sql) {
        databaseClient.sql(sql).then().block();
    }

    private List<Reply> concurrently(int requests, IntFunction<Reply> call) throws Exception {
        CyclicBarrier start = new CyclicBarrier(requests);
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return call.apply(index);
                }));
            }
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) {
                replies.add(future.get(30, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Reply send(WebTestClient.ResponseSpec response) {
        EntityExchangeResult<String> result = response.expectBody(String.class).returnResult();
        return new Reply(result.getStatus().value(), result.getResponseBody(),
                "true".equals(result.getResponseHeaders().getFirst("Idempotent-Replayed")));
    }

    private static Map<Integer, Long> statuses(List<Reply> replies) {
        return replies.stream().collect(Collectors.groupingBy(Reply::status, Collectors.counting()));
    }

    private record Reply(int status, String body, boolean replayed) {
    }
}
