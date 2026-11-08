package com.banking.credit_service.infrastructure.config;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.r2dbc.init.R2dbcScriptDatabaseInitializer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client imports its database location, business zone and Customer Service settings
 * from Config Server and its database credentials from Vault, both through
 * spring.config.import, and schema.sql creates the tables in the empty credits_db at startup.
 */
class ConfigServerClientIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private R2dbcScriptDatabaseInitializer schemaInitializer;

    @Autowired
    private CreditProperties creditProperties;

    @Autowired
    private ClientsProperties clientsProperties;

    @Test
    void importsSettingsFromConfigServer() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("credit-service");
        assertThat(CONFIG_SERVER.requestedPaths()).contains("/credit-service/default");
        assertThat(environment.getProperty("spring.r2dbc.url")).isEqualTo(r2dbcUrl());
        assertThat(creditProperties.businessZone()).isEqualTo(ZoneId.of("America/Lima"));
        ClientsProperties.Customer customer = clientsProperties.customer();
        assertThat(customer.baseUrl()).hasToString(CUSTOMERS.url());
        assertThat(customer.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(customer.circuitBreaker().minimumNumberOfCalls()).isEqualTo(5);
        assertThat(customer.circuitBreaker().waitDurationInOpenState()).isEqualTo(Duration.ofSeconds(60));
    }

    /** The database user and password come from Vault; the Config Server never serves them. */
    @Test
    void readsTheDatabaseCredentialsFromVault() {
        assertThat(sourceOf("spring.r2dbc.password")).contains("secret/credit-service");
        assertThat(sourceOf("spring.r2dbc.username")).contains("secret/credit-service");
        assertThat(sourceOf("spring.r2dbc.url")).contains("configserver");
        assertThat(environment.getProperty("spring.r2dbc.password")).isEqualTo(POSTGRES.getPassword());
        assertThat(environment.getProperty("spring.cloud.vault.authentication")).isEqualTo("APPROLE");
    }

    /** Name of the first (winning) property source that defines {@code key}. */
    private String sourceOf(String key) {
        return ((ConfigurableEnvironment) environment).getPropertySources().stream()
                // Boot's view over all the other sources, not a source of its own.
                .filter(source -> !"configurationProperties".equals(source.getName()))
                .filter(source -> source.containsProperty(key))
                .map(PropertySource::getName)
                .findFirst()
                .orElse("none");
    }

    @Test
    void circuitBreakerUsesTheImportedSettings() {
        var config = customerCircuitBreaker().getCircuitBreakerConfig();

        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
    }

    @Test
    void schemaScriptCreatedTheSchemaInCreditsDatabaseFromScratch() {
        assertThat(databaseClient.sql("SELECT current_database()").map(row -> row.get(0, String.class)).one().block())
                .isEqualTo(DATABASE);
        assertThat(databaseClient.sql("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' ORDER BY table_name
                """).map(row -> row.get(0, String.class)).all().collectList().block())
                .as("the three tables of schema.sql and nothing else")
                .containsExactly("credit_movements", "credit_operations", "credits");
        assertThat(count("""
                SELECT COUNT(*) FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND indexname IN ('uk_credits_personal_loan_active', 'ix_credits_customer_filters',
                                     'ix_credits_opened_at', 'ix_credit_movements_history',
                                     'ix_credit_movements_occurred_at', 'ix_credit_operations_credit')
                """, Map.of())).isEqualTo(6);
    }

    /** What every restart does: schema.sql runs again over a database that already has data. */
    @Test
    void schemaScriptRunsAgainOnARestartAndKeepsTheData() {
        CreditResponse card = openCard(personalCustomer(), "1000.00");
        charge(card.getId(), "300.00");

        assertThat(schemaInitializer.initializeDatabase()).as("schema.sql was found and ran").isTrue();

        assertThat(creditCount()).isEqualTo(1);
        assertThat(movementCount(card.getId())).isEqualTo(1);
        assertThat(operationCount()).isEqualTo(1);
        assertMoney(storedDebt(card.getId()), "300.00");
        assertMoney(charge(card.getId(), "100.00").getOutstandingDebtAfter(), "400.00");
    }

    @Test
    void moneyColumnsKeepTwelveDigitsAndTwoDecimals() {
        assertThat(count("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND data_type = 'numeric'
                   AND (numeric_precision <> 14 OR numeric_scale <> 2)
                """, Map.of())).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND data_type = 'numeric'
                """, Map.of())).as("3 in credits, 2 in credit_movements, 3 in credit_operations").isEqualTo(8);
    }
}
