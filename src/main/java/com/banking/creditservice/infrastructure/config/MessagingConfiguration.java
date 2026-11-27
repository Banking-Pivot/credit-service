package com.banking.creditservice.infrastructure.config;

import com.banking.creditservice.infrastructure.adapter.in.kafka.CreditCommandListener;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka pieces of the service: the topics it uses and what happens to a command that fails.
 *
 * <p>The topics are declared at startup and created when they are missing; declaring a topic that
 * exists changes nothing, so every service that uses a topic can declare it.
 */
@Configuration
public class MessagingConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(MessagingConfiguration.class);

  /** Records with the same key share a partition, so three partitions keep the order per key. */
  private static final int PARTITIONS = 3;

  /** The local broker is a single node. */
  private static final int REPLICAS = 1;

  /** Topic the service reads its commands from; the key is the credit. */
  @Bean
  public NewTopic creditCommandsTopic(CreditProperties properties) {
    return topic(properties.messaging().commandsTopic());
  }

  /** Topic the answers go to, read by Transaction Service; the key is the transaction. */
  @Bean
  public NewTopic transactionRepliesTopic(CreditProperties properties) {
    return topic(properties.messaging().repliesTopic());
  }

  /**
   * What the listener container does when a command fails for a technical reason: it logs the
   * attempt, tries the same command again after the configured wait, the configured number of
   * times, and then logs it and moves on. Nothing is lost by moving on, because Transaction Service
   * sends again a command that got no answer.
   *
   * @param properties wait and number of retries
   * @return the error handler Spring Boot gives to the listener container
   */
  @Bean
  public CommonErrorHandler commandErrorHandler(CreditProperties properties) {
    CreditProperties.Messaging settings = properties.messaging();
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            MessagingConfiguration::giveUp,
            new FixedBackOff(settings.retryInterval().toMillis(), settings.maxRetries()));
    // Each failed attempt is logged in one line, without the message of the error.
    handler.setLogLevel(KafkaException.Level.DEBUG);
    handler.setRetryListeners(
        (command, failure, attempt) -> CreditCommandListener.attemptFailed(command, failure));
    return handler;
  }

  private static NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICAS).build();
  }

  // The type only: messages of database and broker errors do not belong in the log.
  private static void giveUp(ConsumerRecord<?, ?> command, Exception failure) {
    if (LOG.isErrorEnabled()) {
      LOG.error(
          "Command left unanswered after the last retry. topic={}, partition={}, offset={},"
              + " failureType={}",
          command.topic(),
          command.partition(),
          command.offset(),
          NestedExceptionUtils.getMostSpecificCause(failure).getClass().getName());
    }
  }
}
