package com.banking.creditservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.creditservice.infrastructure.config.CreditProperties;
import com.banking.creditservice.infrastructure.config.MessagingConfiguration;
import com.banking.creditservice.infrastructure.correlation.CorrelationId;
import com.banking.creditservice.support.PostgresIntegrationTest;
import com.banking.creditservice.support.StubTransactionService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The command "pay this credit" over a real Kafka broker: what Transaction Service sends to the
 * commands topic and what it reads back from the replies topic.
 */
class PayCreditCommandTest extends PostgresIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> LOGGERS =
      List.of(
          CreditCommandListener.class.getName(),
          PayCreditCommandProcessor.class.getName(),
          MessagingConfiguration.class.getName());

  private static StubTransactionService transactions;

  @Autowired private CreditProperties properties;

  private final CapturedLogs logs = new CapturedLogs();

  @BeforeEach
  void connect() {
    if (transactions == null) {
      // After the Spring context is up, so the topics already exist with their partitions.
      transactions = StubTransactionService.start(KAFKA.getBootstrapServers());
    }
    logs.start();
    LOGGERS.forEach(name -> logger(name).addAppender(logs));
  }

  @AfterEach
  void disconnect() {
    LOGGERS.forEach(name -> logger(name).detachAppender(logs));
    repairOperationLog();
  }

  @Nested
  class Payment {

    @Test
    void aCommandPaysTheDebtAndIsAnsweredAsPaid() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();

      send(
          card.getId(),
          payCredit(
              transactionId, operationId, card.getId(), "100.00", "PEN", "  Paid from account  "));

      ConsumerRecord<String, String> answer = onlyAnswer(transactionId);
      assertThat(answer.key()).isEqualTo(transactionId.toString());
      assertThat(answer.value())
          .as("money keeps its two decimals and the fields of a rejection are left out")
          .contains("\"amount\":100.00", "\"debtAfter\":200.00")
          .doesNotContain("code", "message");
      Reply reply = read(answer);
      assertThat(reply.type()).isEqualTo("CREDIT_PAID");
      assertThat(reply.transactionId()).isEqualTo(transactionId);
      assertThat(reply.operationId()).isEqualTo(operationId);
      assertMoney(reply.amount(), "100.00");
      assertMoney(reply.debtAfter(), "200.00");

      assertMoney(storedDebt(card.getId()), "200.00");
      MovementResponse movement = movementOf(card.getId(), operationId);
      assertThat(movement.getType().getValue()).isEqualTo("PAYMENT");
      assertThat(movement.getDescription()).isEqualTo("Paid from account");
      assertMoney(movement.getOutstandingDebtAfter(), "200.00");
      assertThat(reply.occurredAt()).isEqualTo(movement.getOccurredAt().toInstant());
    }

    @Test
    void aLoanIsPaidTheSameWay() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1200.00");
      UUID transactionId = UUID.randomUUID();

      send(loan.getId(), payCredit(transactionId, UUID.randomUUID(), loan.getId(), "1200.00"));

      Reply reply = read(onlyAnswer(transactionId));
      assertThat(reply.type()).isEqualTo("CREDIT_PAID");
      assertMoney(reply.debtAfter(), "0.00");
      assertMoney(storedDebt(loan.getId()), "0.00");
    }

    @Test
    void fieldsTheServiceDoesNotKnowAreIgnored() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      Map<String, Object> command =
          fields(transactionId, UUID.randomUUID(), card.getId(), new BigDecimal("50.00"), "PEN");
      command.put("priority", 3);
      command.put("origin", Map.of("service", "transaction-service"));

      send(card.getId(), json(command));

      assertThat(read(onlyAnswer(transactionId)).type()).isEqualTo("CREDIT_PAID");
      assertMoney(storedDebt(card.getId()), "250.00");
    }
  }

  @Nested
  class DueDate {

    private static final Duration PAST_THE_DUE_DATE = Duration.ofDays(30);

    @Test
    void aCommandPaysAnOverdueCreditAndBringsItUpToDate() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");
      clock.advance(PAST_THE_DUE_DATE);
      assertThat(credit(loan.getId()).getOverdue()).isTrue();
      UUID transactionId = UUID.randomUUID();
      // The command names the product only: whoever owns the paying account, it is accepted.
      CUSTOMERS.fail(503);
      int callsBefore = CUSTOMERS.calls();

      send(loan.getId(), payCredit(transactionId, UUID.randomUUID(), loan.getId(), "50.00"));

      Reply reply = read(onlyAnswer(transactionId));
      assertThat(reply.type()).isEqualTo("CREDIT_PAID");
      assertMoney(reply.debtAfter(), "950.00");
      CreditResponse paid = credit(loan.getId());
      assertThat(paid.getOverdue()).isFalse();
      assertThat(paid.getDueDate()).isEqualTo(LocalDate.of(2027, 1, 15));
      assertThat(CUSTOMERS.calls()).isEqualTo(callsBefore);
    }

    @Test
    void aCommandBelowTheMinimumIsPaidAndLeavesTheCreditOverdue() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");
      clock.advance(PAST_THE_DUE_DATE);
      UUID transactionId = UUID.randomUUID();

      send(loan.getId(), payCredit(transactionId, UUID.randomUUID(), loan.getId(), "10.00"));

      assertThat(read(onlyAnswer(transactionId)).type()).isEqualTo("CREDIT_PAID");
      CreditResponse paid = credit(loan.getId());
      assertThat(paid.getOverdue()).isTrue();
      assertThat(paid.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
      assertMoney(storedDebt(loan.getId()), "990.00");
    }

    @Test
    void aCommandThatPaysEverythingClearsTheDueDate() {
      CreditResponse loan = openPersonalLoan(personalCustomer(), "1000.00");
      UUID transactionId = UUID.randomUUID();

      send(loan.getId(), payCredit(transactionId, UUID.randomUUID(), loan.getId(), "1000.00"));

      assertThat(read(onlyAnswer(transactionId)).type()).isEqualTo("CREDIT_PAID");
      assertThat(credit(loan.getId()).getDueDate()).isNull();
    }
  }

  @Nested
  class Idempotency {

    @Test
    void aRepeatedCommandIsAnsweredTheSameAndPaysOnce() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      String command = payCredit(transactionId, UUID.randomUUID(), card.getId(), "100.00");

      send(card.getId(), command);
      send(card.getId(), command);

      List<ConsumerRecord<String, String>> answers = transactions.awaitReplies(transactionId, 2);
      assertThat(answers).hasSize(2);
      assertThat(answers.get(1).value()).isEqualTo(answers.get(0).value());
      assertThat(read(answers.get(0)).type()).isEqualTo("CREDIT_PAID");
      assertMoney(storedDebt(card.getId()), "200.00");
      assertThat(movementCount(card.getId())).as("the charge and one payment").isEqualTo(2);
    }

    @Test
    void aCommandThatRepeatsAPaymentMadeOverRestAnswersWithThatPayment() {
      CreditResponse card = cardWithDebt("300.00");
      UUID operationId = UUID.randomUUID();
      OperationResponse paid = created(payRequest(card.getId(), operationId, "100.00"));
      pay(card.getId(), "50.00");
      UUID transactionId = UUID.randomUUID();

      send(card.getId(), payCredit(transactionId, operationId, card.getId(), "100.00"));

      Reply reply = read(onlyAnswer(transactionId));
      assertThat(reply.type()).isEqualTo("CREDIT_PAID");
      assertMoney(reply.debtAfter(), "200.00");
      assertThat(reply.occurredAt()).isEqualTo(paid.getOccurredAt().toInstant());
      assertMoney(storedDebt(card.getId()), "150.00");
    }

    @Test
    void anOperationIdUsedWithOtherDataIsRejected() {
      CreditResponse card = cardWithDebt("300.00");
      UUID operationId = UUID.randomUUID();
      created(payRequest(card.getId(), operationId, "100.00"));
      UUID transactionId = UUID.randomUUID();

      send(card.getId(), payCredit(transactionId, operationId, card.getId(), "60.00"));

      Reply reply = rejected(transactionId, "OPERATION_ID_CONFLICT");
      assertThat(reply.operationId()).isEqualTo(operationId);
      assertThat(reply.message())
          .isEqualTo(
              restMessage(payRequest(card.getId(), operationId, "60.00"), "OPERATION_ID_CONFLICT"));
      assertMoney(storedDebt(card.getId()), "200.00");
    }
  }

  @Nested
  class Rejection {

    @Test
    void aPaymentAboveTheDebtIsRejectedWithTheCodeAndMessageOfTheRestApi() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();

      send(card.getId(), payCredit(transactionId, operationId, card.getId(), "300.01"));

      ConsumerRecord<String, String> answer = onlyAnswer(transactionId);
      assertThat(answer.value()).doesNotContain("amount", "debtAfter", "occurredAt");
      Reply reply = read(answer);
      assertThat(reply.type()).isEqualTo("CREDIT_PAYMENT_REJECTED");
      assertThat(reply.transactionId()).isEqualTo(transactionId);
      assertThat(reply.operationId()).isEqualTo(operationId);
      assertThat(reply.code()).isEqualTo("PAYMENT_EXCEEDS_DEBT");
      assertThat(reply.message())
          .isEqualTo(
              restMessage(
                  payRequest(card.getId(), UUID.randomUUID(), "300.01"), "PAYMENT_EXCEEDS_DEBT"));
      assertNothingWasPaid(card.getId(), "300.00");
    }

    @Test
    void aRejectedOperationIdStaysFreeForACorrectedCommand() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      send(card.getId(), payCredit(transactionId, operationId, card.getId(), "300.01"));
      rejected(transactionId, "PAYMENT_EXCEEDS_DEBT");

      send(card.getId(), payCredit(transactionId, operationId, card.getId(), "300.00"));

      List<ConsumerRecord<String, String>> answers = transactions.awaitReplies(transactionId, 2);
      assertThat(read(answers.get(1)).type()).isEqualTo("CREDIT_PAID");
      assertMoney(storedDebt(card.getId()), "0.00");
    }

    @Test
    void anotherCurrencyIsRejected() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();

      send(
          card.getId(),
          payCredit(transactionId, UUID.randomUUID(), card.getId(), "100.00", "USD", null));

      Reply reply = rejected(transactionId, "CURRENCY_MISMATCH");
      assertThat(reply.message())
          .isEqualTo(
              restMessage(
                  payRequest(card.getId(), UUID.randomUUID(), "100.00", "USD"),
                  "CURRENCY_MISMATCH"));
      assertNothingWasPaid(card.getId(), "300.00");
    }

    @Test
    void anAmountThatIsNotMoneyIsRejected() {
      CreditResponse card = cardWithDebt("300.00");
      UUID zero = UUID.randomUUID();
      UUID negative = UUID.randomUUID();
      UUID threeDecimals = UUID.randomUUID();

      send(card.getId(), payCredit(zero, UUID.randomUUID(), card.getId(), "0.00"));
      send(card.getId(), payCredit(negative, UUID.randomUUID(), card.getId(), "-5.00"));
      send(card.getId(), payCredit(threeDecimals, UUID.randomUUID(), card.getId(), "10.005"));

      rejected(zero, "INVALID_AMOUNT");
      rejected(negative, "INVALID_AMOUNT");
      rejected(threeDecimals, "INVALID_AMOUNT");
      assertNothingWasPaid(card.getId(), "300.00");
    }

    @Test
    void anUnknownCreditIsRejected() {
      UUID unknown = UUID.randomUUID();
      UUID transactionId = UUID.randomUUID();

      send(unknown, payCredit(transactionId, UUID.randomUUID(), unknown, "100.00"));

      Reply reply = rejected(transactionId, "CREDIT_NOT_FOUND");
      assertThat(reply.message())
          .isEqualTo(
              restMessage(payRequest(unknown, UUID.randomUUID(), "100.00"), "CREDIT_NOT_FOUND"));
      assertThat(operationCount()).isZero();
    }

    @Test
    void aClosedCreditIsRejected() {
      CreditResponse card = openCard(personalCustomer(), "1000.00");
      webTestClient
          .delete()
          .uri("/api/v1/credits/{id}", card.getId())
          .header(HttpHeaders.IF_MATCH, "\"0\"")
          .exchange()
          .expectStatus()
          .isNoContent();
      UUID transactionId = UUID.randomUUID();

      send(card.getId(), payCredit(transactionId, UUID.randomUUID(), card.getId(), "100.00"));

      Reply reply = rejected(transactionId, "CREDIT_CLOSED");
      assertThat(reply.message())
          .isEqualTo(
              restMessage(payRequest(card.getId(), UUID.randomUUID(), "100.00"), "CREDIT_CLOSED"));
      assertThat(operationCount()).isZero();
    }

    @Test
    void aCommandWithWrongOrMissingDataIsRejectedAsInvalid() {
      CreditResponse card = cardWithDebt("300.00");
      UUID unknownCurrency = UUID.randomUUID();
      UUID noCurrency = UUID.randomUUID();
      UUID noAmount = UUID.randomUUID();
      UUID noCredit = UUID.randomUUID();
      UUID longDescription = UUID.randomUUID();
      BigDecimal amount = new BigDecimal("100.00");

      send(
          card.getId(),
          json(fields(unknownCurrency, UUID.randomUUID(), card.getId(), amount, "EUR")));
      send(card.getId(), json(fields(noCurrency, UUID.randomUUID(), card.getId(), amount, null)));
      send(card.getId(), json(fields(noAmount, UUID.randomUUID(), card.getId(), null, "PEN")));
      send(card.getId(), json(fields(noCredit, UUID.randomUUID(), "card-7", amount, "PEN")));
      send(
          card.getId(),
          payCredit(
              longDescription, UUID.randomUUID(), card.getId(), "100.00", "PEN", "x".repeat(141)));

      List.of(unknownCurrency, noCurrency, noAmount, noCredit, longDescription)
          .forEach(transactionId -> rejected(transactionId, "INVALID_REQUEST"));
      assertNothingWasPaid(card.getId(), "300.00");
    }
  }

  @Nested
  class Failures {

    @Test
    void aTechnicalFailureIsTriedAgainAndAnsweredOnceItPasses() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      breakOperationLog();
      try {
        send(card.getId(), payCredit(transactionId, UUID.randomUUID(), card.getId(), "100.00"));
        await().atMost(PATIENCE).until(() -> failedAttempts() >= 1);
        assertThat(transactions.repliesTo(transactionId))
            .as("a technical failure is not answered")
            .isEmpty();
      } finally {
        repairOperationLog();
      }

      Reply reply = read(onlyAnswer(transactionId));

      assertThat(reply.type()).isEqualTo("CREDIT_PAID");
      assertMoney(reply.debtAfter(), "200.00");
      assertMoney(storedDebt(card.getId()), "200.00");
      assertThat(movementCount(card.getId())).isEqualTo(2);
      assertThat(givenUp()).isZero();
    }

    @Test
    void aCommandThatKeepsFailingIsLeftAndAnsweredWhenItIsSentAgain() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      String command = payCredit(transactionId, UUID.randomUUID(), card.getId(), "100.00");
      breakOperationLog();
      try {
        send(card.getId(), command);
        await().atMost(PATIENCE).until(() -> givenUp() == 1);
      } finally {
        repairOperationLog();
      }
      assertThat(failedAttempts())
          .as("the first attempt and every retry")
          .isEqualTo(properties.messaging().maxRetries() + 1);
      assertThat(transactions.repliesTo(transactionId)).isEmpty();
      assertMoney(storedDebt(card.getId()), "300.00");

      send(card.getId(), command);

      assertThat(read(onlyAnswer(transactionId)).type()).isEqualTo("CREDIT_PAID");
      assertMoney(storedDebt(card.getId()), "200.00");
    }

    @Test
    void recordsThatCannotBeAnsweredAreSkippedAndTheNextCommandIsHandled() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      Map<String, Object> otherType =
          fields(UUID.randomUUID(), operationId, card.getId(), new BigDecimal("100.00"), "PEN");
      otherType.put("type", "OPEN_CREDIT");
      Map<String, Object> noTransaction =
          fields(null, operationId, card.getId(), new BigDecimal("100.00"), "PEN");
      Map<String, Object> noOperation =
          fields(UUID.randomUUID(), "payment-7", card.getId(), new BigDecimal("100.00"), "PEN");
      int answersBefore = transactions.replies().size();

      // Same key: one partition, so they are handled in this order.
      send(card.getId(), "not json at all");
      send(card.getId(), "[1, 2, 3]");
      send(card.getId(), "null");
      send(card.getId(), null);
      send(card.getId(), "{\"type\":\"PAY_CREDIT\",\"amount\":\"a hundred\"}");
      send(card.getId(), json(otherType));
      send(card.getId(), json(noTransaction));
      send(card.getId(), json(noOperation));
      send(card.getId(), payCredit(transactionId, operationId, card.getId(), "100.00"));

      assertThat(read(onlyAnswer(transactionId)).type()).isEqualTo("CREDIT_PAID");
      assertThat(transactions.replies()).hasSize(answersBefore + 1);
      assertThat(skipped()).isEqualTo(8);
      assertThat(failedAttempts()).as("a skipped record is not a failure").isZero();
      assertThat(logs.events())
          .as("the value of a record is never logged")
          .noneMatch(event -> event.getFormattedMessage().contains("not json at all"));
      assertMoney(storedDebt(card.getId()), "200.00");
    }
  }

  @Nested
  class Correlation {

    private static final String RECEIVED = "saga-2026.11_20-B";

    @Test
    void theIdentifierOfTheCommandIsInTheAnswerAndInTheLog() {
      CreditResponse card = cardWithDebt("300.00");
      UUID transactionId = UUID.randomUUID();

      transactions.send(
          card.getId().toString(),
          payCredit(transactionId, UUID.randomUUID(), card.getId(), "100.00"),
          RECEIVED);

      assertThat(StubTransactionService.correlationIdOf(onlyAnswer(transactionId)))
          .isEqualTo(RECEIVED);
      await().atMost(PATIENCE).until(() -> !eventsAbout(transactionId).isEmpty());
      assertThat(eventsAbout(transactionId))
          .allSatisfy(
              event ->
                  assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationId.KEY, RECEIVED));
    }

    @Test
    void aRejectionCarriesTheIdentifierToo() {
      UUID unknown = UUID.randomUUID();
      UUID transactionId = UUID.randomUUID();

      transactions.send(
          unknown.toString(),
          payCredit(transactionId, UUID.randomUUID(), unknown, "100.00"),
          RECEIVED);

      ConsumerRecord<String, String> answer = onlyAnswer(transactionId);
      assertThat(read(answer).code()).isEqualTo("CREDIT_NOT_FOUND");
      assertThat(StubTransactionService.correlationIdOf(answer)).isEqualTo(RECEIVED);
    }

    @Test
    void aCommandWithoutAUsableIdentifierGetsANewOne() {
      CreditResponse card = cardWithDebt("300.00");
      UUID none = UUID.randomUUID();
      UUID unusable = UUID.randomUUID();

      send(card.getId(), payCredit(none, UUID.randomUUID(), card.getId(), "10.00"));
      transactions.send(
          card.getId().toString(),
          payCredit(unusable, UUID.randomUUID(), card.getId(), "10.00"),
          "two words");

      String first = StubTransactionService.correlationIdOf(onlyAnswer(none));
      String second = StubTransactionService.correlationIdOf(onlyAnswer(unusable));
      assertThat(first).matches("[0-9a-f-]{36}");
      assertThat(second).matches("[0-9a-f-]{36}").isNotEqualTo(first);
    }
  }

  // ----- commands

  private void send(UUID key, String value) {
    transactions.send(key.toString(), value, null);
  }

  private static String payCredit(
      UUID transactionId, UUID operationId, UUID creditId, String amount) {
    return payCredit(transactionId, operationId, creditId, amount, "PEN", null);
  }

  private static String payCredit(
      UUID transactionId,
      UUID operationId,
      UUID creditId,
      String amount,
      String currency,
      String description) {
    Map<String, Object> command =
        fields(transactionId, operationId, creditId, new BigDecimal(amount), currency);
    if (description != null) {
      command.put("description", description);
    }
    return json(command);
  }

  /** The fields of a PAY_CREDIT command; a null value leaves its field out. */
  private static Map<String, Object> fields(
      Object transactionId,
      Object operationId,
      Object creditId,
      BigDecimal amount,
      String currency) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "PAY_CREDIT");
    put(command, "transactionId", transactionId);
    put(command, "operationId", operationId);
    put(command, "creditId", creditId);
    put(command, "amount", amount);
    put(command, "currency", currency);
    return command;
  }

  private static void put(Map<String, Object> command, String field, Object value) {
    if (value != null) {
      command.put(field, value instanceof BigDecimal ? value : value.toString());
    }
  }

  private static String json(Map<String, Object> command) {
    return JSON.writeValueAsString(command);
  }

  // ----- answers

  private ConsumerRecord<String, String> onlyAnswer(UUID transactionId) {
    List<ConsumerRecord<String, String>> answers = transactions.awaitReplies(transactionId, 1);
    assertThat(answers).hasSize(1);
    return answers.get(0);
  }

  private Reply rejected(UUID transactionId, String code) {
    Reply reply = read(onlyAnswer(transactionId));
    assertThat(reply.type()).isEqualTo("CREDIT_PAYMENT_REJECTED");
    assertThat(reply.code()).isEqualTo(code);
    assertThat(reply.message()).isNotBlank();
    return reply;
  }

  private static Reply read(ConsumerRecord<String, String> answer) {
    return JSON.readValue(answer.value(), Reply.class);
  }

  /** The message the REST API gives for the same failure, after checking its code. */
  private static String restMessage(WebTestClient.ResponseSpec response, String code) {
    return new String(
            response
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo(code)
                .returnResult()
                .getResponseBody())
        .replaceAll(".*\"message\":\"([^\"]*)\".*", "$1");
  }

  // ----- stored state

  private CreditResponse cardWithDebt(String debt) {
    CreditResponse card = openCard(personalCustomer(), "1000.00");
    charge(card.getId(), debt);
    return card;
  }

  private MovementResponse movementOf(UUID creditId, UUID operationId) {
    return movements(creditId).getItems().stream()
        .filter(movement -> operationId.equals(movement.getOperationId()))
        .findFirst()
        .orElseThrow();
  }

  /** The debt is the one before the command and the charge is the only operation recorded. */
  private void assertNothingWasPaid(UUID creditId, String debt) {
    assertMoney(storedDebt(creditId), debt);
    assertThat(movementCount(creditId)).isEqualTo(1);
    assertThat(operationCount()).isEqualTo(1);
  }

  /** Makes every payment fail for a technical reason: the table of operations is not there. */
  private void breakOperationLog() {
    databaseClient
        .sql("ALTER TABLE credit_operations RENAME TO credit_operations_away")
        .then()
        .block();
  }

  private void repairOperationLog() {
    databaseClient
        .sql("ALTER TABLE IF EXISTS credit_operations_away RENAME TO credit_operations")
        .then()
        .block();
  }

  // ----- logs

  private long failedAttempts() {
    return logs.count(Level.WARN, "Command failed");
  }

  private long givenUp() {
    return logs.count(Level.ERROR, "Command left unanswered");
  }

  private long skipped() {
    return logs.count(Level.WARN, "Command skipped");
  }

  private List<ILoggingEvent> eventsAbout(UUID transactionId) {
    return logs.events().stream()
        .filter(event -> event.getFormattedMessage().contains(transactionId.toString()))
        .toList();
  }

  private static Logger logger(String name) {
    return (Logger) LoggerFactory.getLogger(name);
  }

  /** A reply as Transaction Service reads it. */
  record Reply(
      String type,
      UUID transactionId,
      UUID operationId,
      BigDecimal amount,
      BigDecimal debtAfter,
      Instant occurredAt,
      String code,
      String message) {}

  /** Keeps the log events of the Kafka adapter; they are written by the consumer thread. */
  private static final class CapturedLogs extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      // Fixes the message and the MDC of the thread that logged.
      event.prepareForDeferredProcessing();
      events.add(event);
    }

    List<ILoggingEvent> events() {
      return List.copyOf(events);
    }

    long count(Level level, String start) {
      return events.stream()
          .filter(event -> event.getLevel() == level)
          .filter(event -> event.getFormattedMessage().startsWith(start))
          .count();
    }
  }
}
