package com.banking.creditservice.infrastructure.adapter.in.kafka;

import com.banking.creditservice.infrastructure.config.CreditProperties;
import com.banking.creditservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the answer to a command in the replies topic of Transaction Service. The key is the
 * transaction, so the answers of one transaction keep their order, and the correlation identifier
 * of the command travels with the answer.
 */
@Component
class TransactionReplyPublisher {

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper mapper;
  private final CreditProperties.Messaging settings;

  TransactionReplyPublisher(
      KafkaTemplate<String, String> kafka, JsonMapper mapper, CreditProperties properties) {
    this.kafka = kafka;
    this.mapper = mapper;
    this.settings = properties.messaging();
  }

  /**
   * Sends the answer and waits until the broker has it.
   *
   * @param reply the answer to a command
   * @param correlationId identifier the command arrived with
   * @throws RuntimeException when the broker did not take the answer within the limit
   */
  void publish(CreditReply reply, String correlationId) {
    ProducerRecord<String, String> answer =
        new ProducerRecord<>(
            settings.repliesTopic(),
            reply.transactionId().toString(),
            mapper.writeValueAsString(reply));
    answer.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    kafka
        .send(answer)
        .orTimeout(settings.handlingTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .join();
  }
}
