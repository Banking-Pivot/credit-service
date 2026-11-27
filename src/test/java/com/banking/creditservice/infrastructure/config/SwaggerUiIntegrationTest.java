package com.banking.creditservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.support.PostgresIntegrationTest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

/** Swagger UI is reachable and documents the contract file, not a derived spec. */
class SwaggerUiIntegrationTest extends PostgresIntegrationTest {

  private static final String CONTRACT_URL = "/openapi/credit-api.yml";

  @Test
  void servesTheContractFileUnchanged() throws IOException {
    byte[] contract = new ClassPathResource("openapi/credit-api.yml").getContentAsByteArray();

    byte[] served =
        webTestClient
            .get()
            .uri(CONTRACT_URL)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_YAML)
            .expectBody()
            .returnResult()
            .getResponseBodyContent();

    assertThat(served).isEqualTo(contract);
  }

  @Test
  void swaggerUiIsConfiguredWithTheContract() {
    webTestClient
        .get()
        .uri("/v3/api-docs/swagger-config")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.url")
        .isEqualTo(CONTRACT_URL);
  }

  @Test
  void swaggerUiPageIsReachable() {
    webTestClient
        .get()
        .uri("/swagger-ui.html")
        .exchange()
        .expectStatus()
        .is3xxRedirection()
        .expectHeader()
        .valueEquals("Location", "/swagger-ui/index.html")
        .expectBody(Void.class);

    webTestClient
        .get()
        .uri("/swagger-ui/index.html")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Void.class);
  }
}
