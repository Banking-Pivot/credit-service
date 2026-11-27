package com.banking.creditservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class CreditServiceApplicationTests extends PostgresIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void contextLoads() {
    assertThat(context.getBeanNamesForType(CreditServiceApplication.class)).hasSize(1);
  }
}
