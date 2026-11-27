package com.banking.creditservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.port.out.MovementReadPort;
import com.banking.creditservice.application.port.out.MovementWritePort;
import com.banking.creditservice.application.query.PageRequest;
import com.banking.creditservice.domain.model.Movement;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * History of credit products on PostgreSQL: appends on the write side, pages and the latest
 * movements on the read side, both served by {@code ix_credit_movements_history}.
 */
@Component
public class PostgresMovementAdapter implements MovementWritePort, MovementReadPort {

  /** Newest first; the id breaks ties between movements of the same instant. */
  static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

  private final R2dbcEntityTemplate template;

  public PostgresMovementAdapter(R2dbcEntityTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Void> append(Movement movement) {
    return template.insert(MovementRow.from(movement)).then();
  }

  @Override
  public Mono<ResultPage<Movement>> findPage(UUID creditId, PageRequest pageRequest) {
    Criteria ofCredit = where("creditId").is(creditId);
    return Mono.zip(
            template
                .select(
                    query(ofCredit)
                        .sort(NEWEST_FIRST)
                        .offset(pageRequest.offset())
                        .limit(pageRequest.size()),
                    MovementRow.class)
                .map(MovementRow::toDomain)
                .collectList(),
            template.count(query(ofCredit), MovementRow.class))
        .map(page -> ResultPage.of(page.getT1(), pageRequest, page.getT2()));
  }

  @Override
  public Flux<Movement> findLatest(UUID creditId, int limit) {
    return template
        .select(
            query(where("creditId").is(creditId)).sort(NEWEST_FIRST).limit(limit),
            MovementRow.class)
        .map(MovementRow::toDomain);
  }
}
