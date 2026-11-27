package com.banking.creditservice.infrastructure.adapter.in.rest;

import com.banking.creditservice.application.port.in.GetLatestCardMovementsUseCase;
import com.banking.creditservice.application.port.in.ListCreditMovementsUseCase;
import com.banking.creditservice.application.query.GetLatestCardMovementsQuery;
import com.banking.creditservice.application.query.ListCreditMovementsQuery;
import com.banking.creditservice.application.query.PageRequest;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.api.MovementsApiDelegate;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.LatestMovementsResponse;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** REST adapter of the Movements tag: history pages and the last ten movements of a card. */
@Component
public class MovementsApiDelegateImpl implements MovementsApiDelegate {

  private final ListCreditMovementsUseCase listMovements;
  private final GetLatestCardMovementsUseCase latestMovements;
  private final CreditRestMapper mapper;

  /** Takes the two movement queries and the mapper that renders their results. */
  public MovementsApiDelegateImpl(
      ListCreditMovementsUseCase listMovements,
      GetLatestCardMovementsUseCase latestMovements,
      CreditRestMapper mapper) {
    this.listMovements = listMovements;
    this.latestMovements = latestMovements;
    this.mapper = mapper;
  }

  @Override
  public Mono<ResponseEntity<MovementPageResponse>> listCreditMovements(
      UUID creditId, Integer page, Integer size, ServerWebExchange exchange) {
    return Mono.fromSupplier(
            () -> new ListCreditMovementsQuery(creditId, PageRequest.of(page, size)))
        .flatMap(listMovements::execute)
        .map(result -> ResponseEntity.ok(mapper.toMovementPage(result)));
  }

  @Override
  public Mono<ResponseEntity<LatestMovementsResponse>> getLatestCardMovements(
      UUID creditId, ServerWebExchange exchange) {
    return latestMovements
        .execute(new GetLatestCardMovementsQuery(creditId))
        .map(movements -> ResponseEntity.ok(mapper.toLatestMovements(creditId, movements)));
  }
}
