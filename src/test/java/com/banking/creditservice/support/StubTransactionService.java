package com.banking.creditservice.support;

import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Stand-in for Transaction Service on the real broker of the tests: it writes commands in the
 * commands topic of Credit Service and keeps every record that appears in the replies topic.
 */
public final class StubTransactionService {

  public static final String COMMANDS_TOPIC = "banking.credit.commands";
  public static final String REPLIES_TOPIC = "banking.transaction.replies";
  public static final String CORRELATION_HEADER = "X-Correlation-ID";

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final KafkaProducer<String, String> producer;
  private final List<ConsumerRecord<String, String>> replies = new CopyOnWriteArrayList<>();

  private StubTransactionService(String bootstrapServers) {
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    Map<String, Object> reading =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG,
            "stub-transaction-service-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    Thread reader = new Thread(() -> read(reading), "stub-transaction-service");
    reader.setDaemon(true);
    reader.start();
  }

  /** Connects to the broker. Call it once the service is up, so its topics exist. */
  public static StubTransactionService start(String bootstrapServers) {
    return new StubTransactionService(bootstrapServers);
  }

  /**
   * Writes a record in the commands topic and waits until the broker has it.
   *
   * @param key key of the record; records with the same key are handled in the order sent
   * @param value value of the record, or null
   * @param correlationId value of the correlation header, or null to send none
   */
  public void send(String key, String value, String correlationId) {
    ProducerRecord<String, String> command = new ProducerRecord<>(COMMANDS_TOPIC, key, value);
    if (correlationId != null) {
      command.headers().add(CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      producer.send(command).get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("The command was not sent.", e);
    }
  }

  /** Every reply read so far, of any transaction, in the order read. */
  public List<ConsumerRecord<String, String>> replies() {
    return List.copyOf(replies);
  }

  /** The replies read so far whose key is the transaction. */
  public List<ConsumerRecord<String, String>> repliesTo(UUID transactionId) {
    return replies.stream().filter(reply -> transactionId.toString().equals(reply.key())).toList();
  }

  /** Waits until the transaction has that many replies and returns them. */
  public List<ConsumerRecord<String, String>> awaitReplies(UUID transactionId, int expected) {
    await().atMost(PATIENCE).until(() -> repliesTo(transactionId).size() >= expected);
    return repliesTo(transactionId);
  }

  /** The correlation header of a reply, or null when it has none. */
  public static String correlationIdOf(ConsumerRecord<String, String> reply) {
    Header header = reply.headers().lastHeader(CORRELATION_HEADER);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private void read(Map<String, Object> settings) {
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(settings)) {
      consumer.subscribe(List.of(REPLIES_TOPIC));
      while (!Thread.currentThread().isInterrupted()) {
        consumer.poll(Duration.ofMillis(100)).forEach(replies::add);
      }
    }
  }
}
