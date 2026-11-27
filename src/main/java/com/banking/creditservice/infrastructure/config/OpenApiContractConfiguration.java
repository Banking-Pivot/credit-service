package com.banking.creditservice.infrastructure.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Publishes the OpenAPI contract the code is generated from, unchanged, at {@code
 * /openapi/credit-api.yml}. Swagger UI points at this file ({@code springdoc.swagger-ui.url})
 * instead of the spec springdoc rebuilds from annotations, which drops headers such as ETag,
 * Location and Idempotent-Replayed.
 */
@Configuration
public class OpenApiContractConfiguration {

  public static final String CONTRACT_PATH = "/openapi/credit-api.yml";

  /** Serves the contract file from the classpath as YAML on {@code GET /openapi/credit-api.yml}. */
  @Bean
  public RouterFunction<ServerResponse> openApiContractRoute() {
    Resource contract = new ClassPathResource("openapi/credit-api.yml");

    return RouterFunctions.route(
        GET(CONTRACT_PATH),
        request -> ServerResponse.ok().contentType(MediaType.APPLICATION_YAML).bodyValue(contract));
  }
}
