package com.banking.creditservice.infrastructure.adapter.in.kafka;

import com.banking.creditservice.infrastructure.config.CreditProperties;
import com.banking.creditservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter of the commands Transaction Service sends to Credit Service: it reads each command
 * of the commands topic, runs it and writes the answer in the replies topic.
 *
 * <p>The listener waits for the payment and for the answer before it returns. It runs on the
 * consumer thread of Kafka, not on an event loop, so waiting there is allowed, and it is what the
 * design needs: commands of one credit arrive with the same key and are handled in order, and the
 * position in the topic moves only after the answer is out. A failure leaves the method as an
 * exception; the error handler of the container logs the attempt in one line ({@link
 * #attemptFailed}), tries the command again and, after the last retry, skips it. Transaction
 * Service then sends it again, and a repeat pays nothing twice.
 */
@Component
public class CreditCommandListener {

  private static final Logger LOG = LoggerFactory.getLogger(CreditCommandListener.class);

  private final PayCreditCommandProcessor processor;
  private final TransactionReplyPublisher replies;
  private final CreditProperties.Messaging settings;

  CreditCommandListener(
      PayCreditCommandProcessor processor,
      TransactionReplyPublisher replies,
      CreditProperties properties) {
    this.processor = processor;
    this.replies = replies;
    this.settings = properties.messaging();
  }

  /** Topic of the listener, read by the expression of {@link #onCommand}. */
  public String getCommandsTopic() {
    return settings.commandsTopic();
  }

  /**
   * Handles one command with its correlation identifier in the logs: the one in the {@code
   * X-Correlation-ID} header of the record, or a new one.
   *
   * @param command record of the commands topic
   */
  @KafkaListener(topics = "#{__listener.commandsTopic}")
  public void onCommand(ConsumerRecord<String, String> command) {
    String correlationId = CorrelationId.accept(correlationHeader(command));
    try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, correlationId)) {
      handle(command, correlationId);
    }
  }

  /**
   * Logs one line for an attempt at a command that failed, with the correlation identifier the
   * command arrived with. The error handler of the container calls it after every attempt that
   * failed, before it tries the command again or leaves it.
   *
   * @param command record of the commands topic that was not answered
   * @param failure what the container got from the listener
   */
  public static void attemptFailed(ConsumerRecord<?, ?> command, Exception failure) {
    if (LOG.isWarnEnabled()) {
      String correlationId = CorrelationId.accept(correlationHeader(command));
      try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, correlationId)) {
        // The type only: messages of database and broker errors do not belong in the log.
        LOG.warn(
            "Command failed and was not answered. topic={}, partition={}, offset={},"
                + " failureType={}",
            command.topic(),
            command.partition(),
            command.offset(),
            thrownByListener(failure).getClass().getName());
      }
    }
  }

  private void handle(ConsumerRecord<String, String> command, String correlationId) {
    Optional<CreditReply> reply =
        processor
            .process(command)
            .contextWrite(context -> context.put(CorrelationId.KEY, correlationId))
            .blockOptional(settings.handlingTimeout());
    if (reply.isPresent()) {
      replies.publish(reply.get(), correlationId);
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "{} -> {}. transactionId={}, partition={}, offset={}",
            PayCreditCommandProcessor.PAY_CREDIT,
            reply.get().type(),
            reply.get().transactionId(),
            command.partition(),
            command.offset());
      }
    }
  }

  // The container wraps what the listener threw; the type to log is the one of the cause.
  private static Throwable thrownByListener(Exception failure) {
    return failure instanceof ListenerExecutionFailedException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  private static String correlationHeader(ConsumerRecord<?, ?> command) {
    Header header = command.headers().lastHeader(CorrelationId.HEADER);
    return header == null || header.value() == null
        ? null
        : new String(header.value(), StandardCharsets.UTF_8);
  }
}
