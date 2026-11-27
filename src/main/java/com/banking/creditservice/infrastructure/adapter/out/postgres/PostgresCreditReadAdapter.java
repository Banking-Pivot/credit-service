package com.banking.creditservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.query.ListCreditsQuery;
import com.banking.creditservice.application.support.BusinessCalendar;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Read side of credit products on PostgreSQL. Reads never lock. Overdue is not stored: every read
 * decides it for today in the business zone.
 */
@Component
public class PostgresCreditReadAdapter implements CreditReadPort {

  /** Oldest first; the id breaks ties between products opened at the same instant. */
  private static final Sort OPENING_ORDER =
      Sort.by(Sort.Order.asc("openedAt"), Sort.Order.asc("id"));

  private final R2dbcEntityTemplate template;
  private final BusinessCalendar calendar;

  public PostgresCreditReadAdapter(R2dbcEntityTemplate template, BusinessCalendar calendar) {
    this.template = template;
    this.calendar = calendar;
  }

  @Override
  public Mono<CreditView> findById(UUID creditId) {
    return template
        .selectOne(query(where("id").is(creditId)), CreditRow.class)
        .map(row -> toView(row, calendar.today()));
  }

  @Override
  public Mono<Boolean> existsById(UUID creditId) {
    return template.exists(query(where("id").is(creditId)), CreditRow.class);
  }

  /** Page and count use the same filters, so totalElements does not depend on the page size. */
  @Override
  public Mono<ResultPage<CreditView>> findPage(ListCreditsQuery request) {
    LocalDate today = calendar.today();
    Criteria filters = filters(request, today);
    return Mono.zip(
            template
                .select(
                    query(filters)
                        .sort(OPENING_ORDER)
                        .offset(request.pageRequest().offset())
                        .limit(request.pageRequest().size()),
                    CreditRow.class)
                .map(row -> toView(row, today))
                .collectList(),
            template.count(query(filters), CreditRow.class))
        .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
  }

  private static Criteria filters(ListCreditsQuery request, LocalDate today) {
    List<Criteria> filters = new ArrayList<>();
    if (request.customerId() != null) {
      filters.add(where("customerId").is(request.customerId()));
    }
    if (request.type() != null) {
      filters.add(where("creditType").is(request.type().name()));
    }
    if (request.status() != null) {
      filters.add(where("status").is(request.status().name()));
    }
    if (request.overdue() != null) {
      filters.add(
          Boolean.TRUE.equals(request.overdue())
              ? OverdueCriteria.overdue(today)
              : OverdueCriteria.notOverdue(today));
    }
    return Criteria.from(filters);
  }

  private static CreditView toView(CreditRow row, LocalDate today) {
    return CreditView.from(row.toDomain(), today);
  }
}
