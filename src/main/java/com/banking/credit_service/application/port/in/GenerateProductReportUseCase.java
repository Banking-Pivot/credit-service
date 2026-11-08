package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.model.ProductReport;
import com.banking.credit_service.application.query.GenerateProductReportQuery;
import reactor.core.publisher.Mono;

/** Query: report by product type and currency for a range of business dates. */
public interface GenerateProductReportUseCase {

    Mono<ProductReport> execute(GenerateProductReportQuery query);
}
