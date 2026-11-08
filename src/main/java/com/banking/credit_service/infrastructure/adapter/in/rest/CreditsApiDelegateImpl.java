package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.application.command.CloseCreditCommand;
import com.banking.credit_service.application.exception.CreditConcurrentModificationException;
import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.port.in.CloseCreditUseCase;
import com.banking.credit_service.application.port.in.GetAvailableCreditUseCase;
import com.banking.credit_service.application.port.in.GetCreditDebtUseCase;
import com.banking.credit_service.application.port.in.GetCreditUseCase;
import com.banking.credit_service.application.port.in.ListCreditsUseCase;
import com.banking.credit_service.application.port.in.OpenCreditUseCase;
import com.banking.credit_service.application.port.in.UpdateCreditUseCase;
import com.banking.credit_service.application.query.GetAvailableCreditQuery;
import com.banking.credit_service.application.query.GetCreditDebtQuery;
import com.banking.credit_service.application.query.GetCreditQuery;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.api.CreditsApiDelegate;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.AvailableBalanceResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditDebtResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditStatus;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditType;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OpenCreditRequest;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.UpdateCreditRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * REST adapter of the Credits tag: implements the contract-generated delegate. The
 * generated {@code CreditsApiController} receives HTTP requests, validates parameters and
 * bodies and calls these methods; this class only translates and chooses status codes.
 */
@Component
public class CreditsApiDelegateImpl implements CreditsApiDelegate {

    private final OpenCreditUseCase openCredit;
    private final UpdateCreditUseCase updateCredit;
    private final CloseCreditUseCase closeCredit;
    private final GetCreditUseCase getCredit;
    private final ListCreditsUseCase listCredits;
    private final GetCreditDebtUseCase getCreditDebt;
    private final GetAvailableCreditUseCase getAvailableCredit;
    private final CreditRestMapper mapper;

    public CreditsApiDelegateImpl(
            OpenCreditUseCase openCredit,
            UpdateCreditUseCase updateCredit,
            CloseCreditUseCase closeCredit,
            GetCreditUseCase getCredit,
            ListCreditsUseCase listCredits,
            GetCreditDebtUseCase getCreditDebt,
            GetAvailableCreditUseCase getAvailableCredit,
            CreditRestMapper mapper
    ) {
        this.openCredit = openCredit;
        this.updateCredit = updateCredit;
        this.closeCredit = closeCredit;
        this.getCredit = getCredit;
        this.listCredits = listCredits;
        this.getCreditDebt = getCreditDebt;
        this.getAvailableCredit = getAvailableCredit;
        this.mapper = mapper;
    }

    @Override
    public Mono<ResponseEntity<CreditResponse>> openCredit(
            Mono<OpenCreditRequest> request,
            ServerWebExchange exchange
    ) {
        return request
                .map(mapper::toCommand)
                .flatMap(openCredit::execute)
                .map(view -> ResponseEntity
                        .created(URI.create("/credits/" + view.id()))
                        .eTag(mapper.toETag(view.version()))
                        .body(mapper.toResponse(view)));
    }

    @Override
    public Mono<ResponseEntity<CreditResponse>> getCredit(UUID creditId, ServerWebExchange exchange) {
        return getCredit.execute(new GetCreditQuery(creditId)).map(this::okWithETag);
    }

    @Override
    public Mono<ResponseEntity<CreditPageResponse>> listCredits(
            Integer page,
            Integer size,
            UUID customerId,
            CreditType type,
            CreditStatus status,
            ServerWebExchange exchange
    ) {
        return Mono.fromSupplier(() -> mapper.toQuery(page, size, customerId, type, status))
                .flatMap(listCredits::execute)
                .map(result -> ResponseEntity.ok(mapper.toCreditPage(result)));
    }

    @Override
    public Mono<ResponseEntity<CreditResponse>> updateCredit(
            UUID creditId,
            String ifMatch,
            Mono<UpdateCreditRequest> request,
            ServerWebExchange exchange
    ) {
        return request
                .flatMap(body -> {
                    OptionalLong expectedVersion = mapper.toExpectedVersion(ifMatch);
                    if (expectedVersion.isEmpty()) {
                        return unmatchableRevision(creditId);
                    }
                    return updateCredit.execute(mapper.toCommand(creditId, expectedVersion.getAsLong(), body));
                })
                .map(this::okWithETag);
    }

    @Override
    public Mono<ResponseEntity<Void>> closeCredit(UUID creditId, String ifMatch, ServerWebExchange exchange) {
        ResponseEntity<Void> noContent = ResponseEntity.noContent().build();
        return Mono.defer(() -> {
                    OptionalLong expectedVersion = mapper.toExpectedVersion(ifMatch);
                    if (expectedVersion.isEmpty()) {
                        return unmatchableRevision(creditId).then();
                    }
                    return closeCredit.execute(new CloseCreditCommand(creditId, expectedVersion.getAsLong()));
                })
                .thenReturn(noContent);
    }

    @Override
    public Mono<ResponseEntity<CreditDebtResponse>> getCreditDebt(UUID creditId, ServerWebExchange exchange) {
        return getCreditDebt.execute(new GetCreditDebtQuery(creditId))
                .map(balance -> ResponseEntity.ok(mapper.toDebtResponse(balance)));
    }

    @Override
    public Mono<ResponseEntity<AvailableBalanceResponse>> getAvailableBalance(
            UUID creditId,
            ServerWebExchange exchange
    ) {
        return getAvailableCredit.execute(new GetAvailableCreditQuery(creditId))
                .map(balance -> ResponseEntity.ok(mapper.toAvailableResponse(balance)));
    }

    private ResponseEntity<CreditResponse> okWithETag(CreditView view) {
        return ResponseEntity.ok()
                .eTag(mapper.toETag(view.version()))
                .body(mapper.toResponse(view));
    }

    /**
     * A well-formed tag that no revision can equal: keep 404 precedence for a missing
     * product, otherwise the precondition fails (412).
     */
    private Mono<CreditView> unmatchableRevision(UUID creditId) {
        return getCredit.execute(new GetCreditQuery(creditId))
                .then(Mono.error(() -> new CreditConcurrentModificationException(creditId)));
    }
}
