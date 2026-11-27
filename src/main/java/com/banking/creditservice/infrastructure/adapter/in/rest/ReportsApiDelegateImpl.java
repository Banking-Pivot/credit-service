package com.banking.creditservice.infrastructure.adapter.in.rest;

import com.banking.creditservice.application.port.in.GenerateProductReportUseCase;
import com.banking.creditservice.application.query.GenerateProductReportQuery;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.api.ReportsApiDelegate;
import com.banking.creditservice.infrastructure.adapter.in.rest.generated.model.ProductReportResponse;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** REST adapter of the Reports tag: report by product for a range of business dates. */
@Component
public class ReportsApiDelegateImpl implements ReportsApiDelegate {

  private final GenerateProductReportUseCase generateProductReport;
  private final CreditRestMapper mapper;

  public ReportsApiDelegateImpl(
      GenerateProductReportUseCase generateProductReport, CreditRestMapper mapper) {
    this.generateProductReport = generateProductReport;
    this.mapper = mapper;
  }

  @Override
  public Mono<ResponseEntity<ProductReportResponse>> getProductReport(
      LocalDate from, LocalDate to, ServerWebExchange exchange) {
    return Mono.fromSupplier(() -> new GenerateProductReportQuery(from, to))
        .flatMap(generateProductReport::execute)
        .map(report -> ResponseEntity.ok(mapper.toResponse(report)));
  }
}
