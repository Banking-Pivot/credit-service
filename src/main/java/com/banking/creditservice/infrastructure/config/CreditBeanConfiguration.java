package com.banking.creditservice.infrastructure.config;

import com.banking.creditservice.application.handler.command.ChargeCreditHandler;
import com.banking.creditservice.application.handler.command.CloseCreditHandler;
import com.banking.creditservice.application.handler.command.CreditOperationExecutor;
import com.banking.creditservice.application.handler.command.OpenCreditHandler;
import com.banking.creditservice.application.handler.command.PayCreditHandler;
import com.banking.creditservice.application.handler.command.UpdateCreditHandler;
import com.banking.creditservice.application.handler.query.GenerateProductReportHandler;
import com.banking.creditservice.application.handler.query.GetAvailableCreditHandler;
import com.banking.creditservice.application.handler.query.GetCreditDebtHandler;
import com.banking.creditservice.application.handler.query.GetCreditHandler;
import com.banking.creditservice.application.handler.query.GetLatestCardMovementsHandler;
import com.banking.creditservice.application.handler.query.ListCreditMovementsHandler;
import com.banking.creditservice.application.handler.query.ListCreditsHandler;
import com.banking.creditservice.application.port.in.ChargeCreditUseCase;
import com.banking.creditservice.application.port.in.CloseCreditUseCase;
import com.banking.creditservice.application.port.in.GenerateProductReportUseCase;
import com.banking.creditservice.application.port.in.GetAvailableCreditUseCase;
import com.banking.creditservice.application.port.in.GetCreditDebtUseCase;
import com.banking.creditservice.application.port.in.GetCreditUseCase;
import com.banking.creditservice.application.port.in.GetLatestCardMovementsUseCase;
import com.banking.creditservice.application.port.in.ListCreditMovementsUseCase;
import com.banking.creditservice.application.port.in.ListCreditsUseCase;
import com.banking.creditservice.application.port.in.OpenCreditUseCase;
import com.banking.creditservice.application.port.in.PayCreditUseCase;
import com.banking.creditservice.application.port.in.UpdateCreditUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.port.out.CreditWritePort;
import com.banking.creditservice.application.port.out.CustomerDirectoryPort;
import com.banking.creditservice.application.port.out.MovementReadPort;
import com.banking.creditservice.application.port.out.MovementWritePort;
import com.banking.creditservice.application.port.out.OperationLogPort;
import com.banking.creditservice.application.port.out.ProductReportPort;
import com.banking.creditservice.application.port.out.TransactionPort;
import com.banking.creditservice.application.support.BusinessCalendar;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application handlers without putting Spring annotations on them. Command handlers use
 * write ports; query handlers use read ports only.
 */
@Configuration
@EnableConfigurationProperties({CreditProperties.class, ClientsProperties.class})
public class CreditBeanConfiguration {

  /**
   * The clock of the service: the system clock in UTC plus {@code credit.clock-offset}, which is
   * zero unless a demo needs to pass a due date without waiting.
   */
  @Bean
  public Clock creditClock(CreditProperties properties) {
    return Clock.offset(Clock.systemUTC(), properties.clockOffset());
  }

  @Bean
  public BusinessCalendar businessCalendar(Clock clock, CreditProperties properties) {
    return new BusinessCalendar(clock, properties.businessZone());
  }

  @Bean
  public CreditOperationExecutor creditOperationExecutor(
      CreditWritePort credits,
      MovementWritePort movements,
      OperationLogPort operations,
      TransactionPort transactions,
      BusinessCalendar calendar) {
    return new CreditOperationExecutor(credits, movements, operations, transactions, calendar);
  }

  @Bean
  public OpenCreditUseCase openCreditUseCase(
      CreditWritePort credits,
      MovementWritePort movements,
      TransactionPort transactions,
      CustomerDirectoryPort customers,
      BusinessCalendar calendar) {
    return new OpenCreditHandler(credits, movements, transactions, customers, calendar);
  }

  @Bean
  public UpdateCreditUseCase updateCreditUseCase(
      CreditWritePort credits, TransactionPort transactions, BusinessCalendar calendar) {
    return new UpdateCreditHandler(credits, transactions, calendar);
  }

  @Bean
  public CloseCreditUseCase closeCreditUseCase(
      CreditWritePort credits, TransactionPort transactions, BusinessCalendar calendar) {
    return new CloseCreditHandler(credits, transactions, calendar);
  }

  @Bean
  public ChargeCreditUseCase chargeCreditUseCase(CreditOperationExecutor executor) {
    return new ChargeCreditHandler(executor);
  }

  @Bean
  public PayCreditUseCase payCreditUseCase(
      CreditOperationExecutor executor, CreditProperties properties) {
    return new PayCreditHandler(executor, properties.billing().minimumPaymentRate());
  }

  @Bean
  public GetCreditUseCase getCreditUseCase(CreditReadPort credits) {
    return new GetCreditHandler(credits);
  }

  @Bean
  public ListCreditsUseCase listCreditsUseCase(CreditReadPort credits) {
    return new ListCreditsHandler(credits);
  }

  @Bean
  public GetCreditDebtUseCase getCreditDebtUseCase(
      CreditReadPort credits, BusinessCalendar calendar) {
    return new GetCreditDebtHandler(credits, calendar);
  }

  @Bean
  public GetAvailableCreditUseCase getAvailableCreditUseCase(
      CreditReadPort credits, BusinessCalendar calendar) {
    return new GetAvailableCreditHandler(credits, calendar);
  }

  @Bean
  public ListCreditMovementsUseCase listCreditMovementsUseCase(
      CreditReadPort credits, MovementReadPort movements) {
    return new ListCreditMovementsHandler(credits, movements);
  }

  @Bean
  public GetLatestCardMovementsUseCase getLatestCardMovementsUseCase(
      CreditReadPort credits, MovementReadPort movements) {
    return new GetLatestCardMovementsHandler(credits, movements);
  }

  @Bean
  public GenerateProductReportUseCase generateProductReportUseCase(
      ProductReportPort reports, BusinessCalendar calendar) {
    return new GenerateProductReportHandler(reports, calendar);
  }
}
