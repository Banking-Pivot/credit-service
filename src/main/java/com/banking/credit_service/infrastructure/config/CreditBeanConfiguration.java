package com.banking.credit_service.infrastructure.config;

import com.banking.credit_service.application.handler.command.ChargeCreditHandler;
import com.banking.credit_service.application.handler.command.CloseCreditHandler;
import com.banking.credit_service.application.handler.command.CreditOperationExecutor;
import com.banking.credit_service.application.handler.command.OpenCreditHandler;
import com.banking.credit_service.application.handler.command.PayCreditHandler;
import com.banking.credit_service.application.handler.command.UpdateCreditHandler;
import com.banking.credit_service.application.handler.query.GenerateProductReportHandler;
import com.banking.credit_service.application.handler.query.GetAvailableCreditHandler;
import com.banking.credit_service.application.handler.query.GetCreditDebtHandler;
import com.banking.credit_service.application.handler.query.GetCreditHandler;
import com.banking.credit_service.application.handler.query.GetLatestCardMovementsHandler;
import com.banking.credit_service.application.handler.query.ListCreditMovementsHandler;
import com.banking.credit_service.application.handler.query.ListCreditsHandler;
import com.banking.credit_service.application.port.in.ChargeCreditUseCase;
import com.banking.credit_service.application.port.in.CloseCreditUseCase;
import com.banking.credit_service.application.port.in.GenerateProductReportUseCase;
import com.banking.credit_service.application.port.in.GetAvailableCreditUseCase;
import com.banking.credit_service.application.port.in.GetCreditDebtUseCase;
import com.banking.credit_service.application.port.in.GetCreditUseCase;
import com.banking.credit_service.application.port.in.GetLatestCardMovementsUseCase;
import com.banking.credit_service.application.port.in.ListCreditMovementsUseCase;
import com.banking.credit_service.application.port.in.ListCreditsUseCase;
import com.banking.credit_service.application.port.in.OpenCreditUseCase;
import com.banking.credit_service.application.port.in.PayCreditUseCase;
import com.banking.credit_service.application.port.in.UpdateCreditUseCase;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.port.out.CreditWritePort;
import com.banking.credit_service.application.port.out.CustomerDirectoryPort;
import com.banking.credit_service.application.port.out.MovementReadPort;
import com.banking.credit_service.application.port.out.MovementWritePort;
import com.banking.credit_service.application.port.out.OperationLogPort;
import com.banking.credit_service.application.port.out.ProductReportPort;
import com.banking.credit_service.application.port.out.TransactionPort;
import com.banking.credit_service.application.support.BusinessCalendar;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the application handlers without putting Spring annotations on them. Command
 * handlers use write ports; query handlers use read ports only.
 */
@Configuration
@EnableConfigurationProperties({CreditProperties.class, ClientsProperties.class})
public class CreditBeanConfiguration {

    @Bean
    public Clock creditClock() {
        return Clock.systemUTC();
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
            BusinessCalendar calendar
    ) {
        return new CreditOperationExecutor(credits, movements, operations, transactions, calendar);
    }

    @Bean
    public OpenCreditUseCase openCreditUseCase(
            CreditWritePort credits,
            MovementWritePort movements,
            TransactionPort transactions,
            CustomerDirectoryPort customers,
            BusinessCalendar calendar
    ) {
        return new OpenCreditHandler(credits, movements, transactions, customers, calendar);
    }

    @Bean
    public UpdateCreditUseCase updateCreditUseCase(
            CreditWritePort credits,
            TransactionPort transactions,
            BusinessCalendar calendar
    ) {
        return new UpdateCreditHandler(credits, transactions, calendar);
    }

    @Bean
    public CloseCreditUseCase closeCreditUseCase(
            CreditWritePort credits,
            TransactionPort transactions,
            BusinessCalendar calendar
    ) {
        return new CloseCreditHandler(credits, transactions, calendar);
    }

    @Bean
    public ChargeCreditUseCase chargeCreditUseCase(CreditOperationExecutor executor) {
        return new ChargeCreditHandler(executor);
    }

    @Bean
    public PayCreditUseCase payCreditUseCase(CreditOperationExecutor executor) {
        return new PayCreditHandler(executor);
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
    public GetCreditDebtUseCase getCreditDebtUseCase(CreditReadPort credits, BusinessCalendar calendar) {
        return new GetCreditDebtHandler(credits, calendar);
    }

    @Bean
    public GetAvailableCreditUseCase getAvailableCreditUseCase(CreditReadPort credits, BusinessCalendar calendar) {
        return new GetAvailableCreditHandler(credits, calendar);
    }

    @Bean
    public ListCreditMovementsUseCase listCreditMovementsUseCase(CreditReadPort credits, MovementReadPort movements) {
        return new ListCreditMovementsHandler(credits, movements);
    }

    @Bean
    public GetLatestCardMovementsUseCase getLatestCardMovementsUseCase(
            CreditReadPort credits,
            MovementReadPort movements
    ) {
        return new GetLatestCardMovementsHandler(credits, movements);
    }

    @Bean
    public GenerateProductReportUseCase generateProductReportUseCase(
            ProductReportPort reports,
            BusinessCalendar calendar
    ) {
        return new GenerateProductReportHandler(reports, calendar);
    }
}
