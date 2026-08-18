package com.coderbank.coderbank_transaction_service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AccountMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Test
    void migratesValidLegacyAccountToChecking() throws Exception {
        String schema = createSchema();
        migrateToV1(schema);
        insertLegacyAccount(schema, "01890f76-9b3c-7cc2-98c4-dc0c0c07398f");

        migrateToLatest(schema);

        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT customer_id, account_type FROM " + schema + ".accounts")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject("customer_id")).isInstanceOf(UUID.class);
            assertThat(result.getString("account_type")).isEqualTo("CHECKING");
        }
    }

    @Test
    void rejectsLegacyAccountWithInvalidCustomerUuid() throws Exception {
        String schema = createSchema();
        migrateToV1(schema);
        insertLegacyAccount(schema, "invalid-customer-id");

        assertThatThrownBy(() -> migrateToLatest(schema))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("contains invalid UUID values");
    }

    @Test
    void rejectsDuplicateLegacyAccountsForCustomer() throws Exception {
        String schema = createSchema();
        migrateToV1(schema);
        String customerId = UUID.randomUUID().toString();
        insertLegacyAccount(schema, customerId);
        insertLegacyAccount(schema, customerId);

        assertThatThrownBy(() -> migrateToLatest(schema))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("duplicate accounts exist for a customer");
    }

    private String createSchema() throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        return schema;
    }

    private void migrateToV1(String schema) {
        flyway(schema, "1").migrate();
    }

    private void migrateToLatest(String schema) {
        flyway(schema, null).migrate();
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertLegacyAccount(String schema, String customerId) throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     INSERT INTO %s.accounts (id, customer_id, balance, currency, created_at)
                     VALUES (?, ?, 0, 'BRL', CURRENT_TIMESTAMP)
                     """.formatted(schema))) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, customerId);
            statement.executeUpdate();
        }
    }
}
