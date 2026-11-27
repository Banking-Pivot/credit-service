package com.banking.creditservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of Credit Service, which keeps the loans and credit cards of the bank's customers:
 * opening, charges, payments, movement history and the product report.
 */
@SpringBootApplication
public class CreditServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CreditServiceApplication.class, args);
  }
}
