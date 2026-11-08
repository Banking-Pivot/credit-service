package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.application.exception.CreditConcurrentModificationException;
import com.banking.credit_service.application.exception.DuplicatePersonalLoanException;
import com.banking.credit_service.application.port.out.CreditWritePort;
import com.banking.credit_service.domain.model.Credit;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

/** Write side of credit products on PostgreSQL. */
@Component
public class PostgresCreditWriteAdapter implements CreditWritePort {

    static final String PERSONAL_LOAN_INDEX = "uk_credits_personal_loan_active";

    private final R2dbcEntityTemplate template;
    private final CreditRowRepository repository;

    public PostgresCreditWriteAdapter(R2dbcEntityTemplate template, CreditRowRepository repository) {
        this.template = template;
        this.repository = repository;
    }

    @Override
    public Mono<Void> insert(Credit credit) {
        return template.insert(CreditRow.from(credit))
                .then()
                .onErrorMap(error -> PostgresErrors.isUniqueViolation(error, PERSONAL_LOAN_INDEX),
                        DuplicatePersonalLoanException::new);
    }

    @Override
    public Mono<Credit> lockById(UUID creditId) {
        return repository.findForUpdateById(creditId).map(CreditRow::toDomain);
    }

    @Override
    public Mono<Void> update(Credit credit, long expectedVersion) {
        Update update = Update.update("outstandingDebt", credit.getOutstandingDebt())
                .set("paymentDueDay", credit.getPaymentDueDay())
                .set("status", credit.getStatus().name())
                .set("updatedAt", credit.getUpdatedAt())
                .set("version", credit.getVersion());
        if (credit.getCreditLimit() != null) {
            // Only cards have a limit; a loan never gets one.
            update = update.set("creditLimit", credit.getCreditLimit());
        }
        if (credit.getClosedAt() != null) {
            // A product never reopens, so closedAt only goes from empty to set.
            update = update.set("closedAt", credit.getClosedAt());
        }
        return template.update(CreditRow.class)
                .matching(query(where("id").is(credit.getId()).and("version").is(expectedVersion)))
                .apply(update)
                .flatMap(rows -> rows == 1
                        ? Mono.<Void>empty()
                        : Mono.error(new CreditConcurrentModificationException(credit.getId())));
    }
}
