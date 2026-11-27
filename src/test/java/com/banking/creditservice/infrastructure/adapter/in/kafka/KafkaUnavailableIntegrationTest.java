package com.banking.creditservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.creditservice.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Kafka is for the commands of Transaction Service only. With no broker at the configured address
 * the service still starts and its REST API works; the context is closed after the class so its
 * listener stops looking for the broker.
 */
@TestPropertySource(
    properties = {
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.kafka.admin.operation-timeout=1s"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaUnavailableIntegrationTest extends PostgresIntegrationTest {

  @Autowired private Environment environment;

  @Test
  void theServiceStartsAndServesRestWithoutABroker() {
    assertThat(environment.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("127.0.0.1:1");

    CreditResponse card = openCard(personalCustomer(), "1000.00");
    charge(card.getId(), "300.00");

    assertMoney(pay(card.getId(), "100.00").getOutstandingDebtAfter(), "200.00");
    assertMoney(storedDebt(card.getId()), "200.00");
  }
}
