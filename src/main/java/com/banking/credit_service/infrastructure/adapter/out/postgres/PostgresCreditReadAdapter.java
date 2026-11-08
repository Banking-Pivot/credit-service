package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.query.ListCreditsQuery;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

/** Read side of credit products on PostgreSQL. Reads never lock. */
@Component
public class PostgresCreditReadAdapter implements CreditReadPort {

    /** Oldest first; the id breaks ties between products opened at the same instant. */
    private static final Sort OPENING_ORDER = Sort.by(Sort.Order.asc("openedAt"), Sort.Order.asc("id"));

    private final R2dbcEntityTemplate template;

    public PostgresCreditReadAdapter(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public Mono<CreditView> findById(UUID creditId) {
        return template.selectOne(query(where("id").is(creditId)), CreditRow.class)
                .map(PostgresCreditReadAdapter::toView);
    }

    @Override
    public Mono<Boolean> existsById(UUID creditId) {
        return template.exists(query(where("id").is(creditId)), CreditRow.class);
    }

    /** Page and count use the same filters, so totalElements does not depend on the page size. */
    @Override
    public Mono<ResultPage<CreditView>> findPage(ListCreditsQuery request) {
        Criteria filters = filters(request);
        return Mono.zip(
                        template.select(query(filters)
                                        .sort(OPENING_ORDER)
                                        .offset(request.pageRequest().offset())
                                        .limit(request.pageRequest().size()), CreditRow.class)
                                .map(PostgresCreditReadAdapter::toView)
                                .collectList(),
                        template.count(query(filters), CreditRow.class))
                .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
    }

    private static Criteria filters(ListCreditsQuery request) {
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
        return Criteria.from(filters);
    }

    private static CreditView toView(CreditRow row) {
        return CreditView.from(row.toDomain());
    }
}
