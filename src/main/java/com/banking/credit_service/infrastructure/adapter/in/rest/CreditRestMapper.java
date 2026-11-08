package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.application.command.OpenCreditCommand;
import com.banking.credit_service.application.command.UpdateCreditCommand;
import com.banking.credit_service.application.model.CreditBalanceView;
import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.model.ProductReport;
import com.banking.credit_service.application.model.RecordedOperation;
import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.query.ListCreditsQuery;
import com.banking.credit_service.application.query.PageRequest;
import com.banking.credit_service.domain.model.Movement;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.AvailableBalanceResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditDebtResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditStatus;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditType;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CurrencyCode;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CustomerType;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.LatestMovementsResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementTotals;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementType;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OpenCreditRequest;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationType;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.ProductReportLine;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.ProductReportResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.UpdateCreditRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebInputException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Translates between generated REST DTOs and application records, and between revisions
 * and strong ETags ({@code "3"}). Generated enums and domain enums share names, so
 * conversion goes through {@code name()}.
 */
@Component
public class CreditRestMapper {

    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\"([0-9]+)\"$");

    public OpenCreditCommand toCommand(OpenCreditRequest request) {
        return new OpenCreditCommand(
                request.getCustomerId(),
                convert(request.getType(), com.banking.credit_service.domain.model.CreditType.class),
                convert(request.getCurrency(), com.banking.credit_service.domain.model.CurrencyCode.class),
                request.getPrincipal(),
                request.getCreditLimit(),
                request.getPaymentDueDay());
    }

    public UpdateCreditCommand toCommand(UUID creditId, long expectedVersion, UpdateCreditRequest request) {
        return new UpdateCreditCommand(creditId, expectedVersion, request.getPaymentDueDay(), request.getCreditLimit());
    }

    public ListCreditsQuery toQuery(Integer page, Integer size, UUID customerId, CreditType type, CreditStatus status) {
        return new ListCreditsQuery(PageRequest.of(page, size), customerId,
                convert(type, com.banking.credit_service.domain.model.CreditType.class),
                convert(status, com.banking.credit_service.domain.model.CreditStatus.class));
    }

    public CreditResponse toResponse(CreditView view) {
        return new CreditResponse()
                .id(view.id())
                .customerId(view.customerId())
                .customerType(convert(view.customerType(), CustomerType.class))
                .type(convert(view.type(), CreditType.class))
                .currency(convert(view.currency(), CurrencyCode.class))
                .principal(view.principal())
                .creditLimit(view.creditLimit())
                .outstandingDebt(view.outstandingDebt())
                .availableCredit(view.availableCredit())
                .paymentDueDay(view.paymentDueDay())
                .status(convert(view.status(), CreditStatus.class))
                .openedAt(utc(view.openedAt()))
                .updatedAt(utc(view.updatedAt()))
                .closedAt(utc(view.closedAt()));
    }

    public CreditPageResponse toCreditPage(ResultPage<CreditView> page) {
        return new CreditPageResponse()
                .items(page.items().stream().map(this::toResponse).toList())
                .page(page.page())
                .size(page.size())
                .totalElements(page.totalElements())
                .totalPages(page.totalPages());
    }

    public CreditDebtResponse toDebtResponse(CreditBalanceView balance) {
        CreditView credit = balance.credit();
        return new CreditDebtResponse()
                .creditId(credit.id())
                .type(convert(credit.type(), CreditType.class))
                .currency(convert(credit.currency(), CurrencyCode.class))
                .status(convert(credit.status(), CreditStatus.class))
                .principal(credit.principal())
                .creditLimit(credit.creditLimit())
                .outstandingDebt(credit.outstandingDebt())
                .asOf(utc(balance.asOf()));
    }

    public AvailableBalanceResponse toAvailableResponse(CreditBalanceView balance) {
        CreditView credit = balance.credit();
        return new AvailableBalanceResponse()
                .creditId(credit.id())
                .currency(convert(credit.currency(), CurrencyCode.class))
                .status(convert(credit.status(), CreditStatus.class))
                .creditLimit(credit.creditLimit())
                .outstandingDebt(credit.outstandingDebt())
                .availableCredit(credit.availableCredit())
                .asOf(utc(balance.asOf()));
    }

    public OperationResponse toResponse(RecordedOperation operation) {
        return new OperationResponse()
                .operationId(operation.operationId())
                .type(convert(operation.type(), OperationType.class))
                .status(OperationResponse.StatusEnum.COMPLETED)
                .creditId(operation.creditId())
                .movementId(operation.movementId())
                .amount(operation.amount())
                .currency(convert(operation.currency(), CurrencyCode.class))
                .outstandingDebtAfter(operation.debtAfter())
                .availableCreditAfter(operation.availableCreditAfter())
                .description(operation.description())
                .occurredAt(utc(operation.occurredAt()));
    }

    public MovementPageResponse toMovementPage(ResultPage<Movement> page) {
        return new MovementPageResponse()
                .items(page.items().stream().map(this::toResponse).toList())
                .page(page.page())
                .size(page.size())
                .totalElements(page.totalElements())
                .totalPages(page.totalPages());
    }

    public LatestMovementsResponse toLatestMovements(UUID creditId, List<Movement> movements) {
        return new LatestMovementsResponse()
                .creditId(creditId)
                .items(movements.stream().map(this::toResponse).toList());
    }

    private MovementResponse toResponse(Movement movement) {
        return new MovementResponse()
                .id(movement.id())
                .creditId(movement.creditId())
                .operationId(movement.operationId())
                .type(convert(movement.type(), MovementType.class))
                .amount(movement.amount())
                .currency(convert(movement.currency(), CurrencyCode.class))
                .outstandingDebtAfter(movement.debtAfter())
                .description(movement.description())
                .occurredAt(utc(movement.occurredAt()));
    }

    public ProductReportResponse toResponse(ProductReport report) {
        return new ProductReportResponse()
                .from(report.from())
                .to(report.to())
                .businessZone(report.businessZone().getId())
                .generatedAt(utc(report.generatedAt()))
                .products(report.lines().stream().map(this::toResponse).toList());
    }

    private ProductReportLine toResponse(ProductReport.Line line) {
        return new ProductReportLine()
                .type(convert(line.type(), CreditType.class))
                .currency(convert(line.currency(), CurrencyCode.class))
                .creditsOpened(line.creditsOpened())
                .creditsClosed(line.creditsClosed())
                .activeCreditsAtEnd(line.activeCreditsAtEnd())
                .outstandingDebtAtEnd(line.outstandingDebtAtEnd())
                .movements(line.movements().stream()
                        .map(totals -> new MovementTotals()
                                .type(convert(totals.type(), MovementType.class))
                                .count(totals.count())
                                .totalAmount(totals.totalAmount()))
                        .toList());
    }

    /** Strong entity tag for a revision, quoted as the contract requires. */
    public String toETag(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Reads the revision from If-Match.
     *
     * @return the revision, or empty when the tag is well formed but can never match one
     * this API issued (leading zeros such as {@code "03"}, or a value beyond the long
     * range): the caller must answer 412, not 400.
     * @throws ServerWebInputException when the header is not one quoted number. The
     *                                 generated controller already validates the pattern; this is a guard.
     */
    public OptionalLong toExpectedVersion(String ifMatch) {
        var matcher = ifMatch == null ? null : STRONG_NUMERIC_ETAG.matcher(ifMatch);
        if (matcher == null || !matcher.matches()) {
            throw new ServerWebInputException("Malformed If-Match header.");
        }
        try {
            long version = Long.parseLong(matcher.group(1));
            return toETag(version).equals(ifMatch) ? OptionalLong.of(version) : OptionalLong.empty();
        } catch (NumberFormatException beyondLongRange) {
            return OptionalLong.empty();
        }
    }

    private static <E extends Enum<E>> E convert(Enum<?> value, Class<E> type) {
        return value == null ? null : Enum.valueOf(type, value.name());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
