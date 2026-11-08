package com.banking.credit_service.infrastructure.adapter.in.rest;

import com.banking.credit_service.application.command.ChargeCreditCommand;
import com.banking.credit_service.application.command.PayCreditCommand;
import com.banking.credit_service.application.model.OperationResult;
import com.banking.credit_service.application.port.in.ChargeCreditUseCase;
import com.banking.credit_service.application.port.in.PayCreditUseCase;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.api.OperationsApiDelegate;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.ChargeRequest;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.PaymentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST adapter of the Operations tag. Each request turns into a command identified by its
 * operationId; a new operation answers 201, a recognized repeat answers 200 with the
 * original result and {@code Idempotent-Replayed: true}.
 */
@Component
public class OperationsApiDelegateImpl implements OperationsApiDelegate {

    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final ChargeCreditUseCase charge;
    private final PayCreditUseCase pay;
    private final CreditRestMapper mapper;

    public OperationsApiDelegateImpl(ChargeCreditUseCase charge, PayCreditUseCase pay, CreditRestMapper mapper) {
        this.charge = charge;
        this.pay = pay;
        this.mapper = mapper;
    }

    @Override
    public Mono<ResponseEntity<OperationResponse>> chargeCredit(
            UUID creditId,
            Mono<ChargeRequest> request,
            ServerWebExchange exchange
    ) {
        return request
                .map(body -> new ChargeCreditCommand(body.getOperationId(), creditId, body.getAmount(),
                        CurrencyCode.valueOf(body.getCurrency().name()), body.getDescription()))
                .flatMap(charge::execute)
                .map(this::toResponse);
    }

    @Override
    public Mono<ResponseEntity<OperationResponse>> payCredit(
            UUID creditId,
            Mono<PaymentRequest> request,
            ServerWebExchange exchange
    ) {
        return request
                .map(body -> new PayCreditCommand(body.getOperationId(), creditId, body.getAmount(),
                        CurrencyCode.valueOf(body.getCurrency().name()), body.getDescription()))
                .flatMap(pay::execute)
                .map(this::toResponse);
    }

    private ResponseEntity<OperationResponse> toResponse(OperationResult result) {
        OperationResponse body = mapper.toResponse(result.operation());
        if (result.replayed()) {
            return ResponseEntity.ok().header(IDEMPOTENT_REPLAYED, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
