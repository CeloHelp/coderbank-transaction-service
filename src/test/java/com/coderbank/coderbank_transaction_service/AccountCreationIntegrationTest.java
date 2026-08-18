package com.coderbank.coderbank_transaction_service;

import com.coderbank.coderbank_transaction_service.repository.AccountCreationRequestRepository;
import com.coderbank.coderbank_transaction_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AccountCreationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountCreationRequestRepository creationRequestRepository;

    @BeforeEach
    void cleanDatabase() {
        creationRequestRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void createsCheckingAccountWithServerDefinedZeroBalance() throws Exception {
        UUID customerId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "BRL")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.accountType").value("CHECKING"))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void replaysSameAccountForSameKeyAndPayload() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String payload = request(customerId, "CHECKING", "BRL");

        String accountId = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String expectedAccountId = com.jayway.jsonpath.JsonPath.read(accountId, "$.accountId");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.accountId").value(expectedAccountId));

        org.assertj.core.api.Assertions.assertThat(accountRepository.count()).isOne();
    }

    @Test
    void rejectsReusedKeyWithDifferentPayload() throws Exception {
        UUID key = UUID.randomUUID();

        createChecking(UUID.randomUUID(), key);

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "CHECKING", "BRL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsDifferentKeyForExistingCustomerAndType() throws Exception {
        UUID customerId = UUID.randomUUID();
        createChecking(customerId, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "BRL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_TYPE_ALREADY_EXISTS"));
    }

    @Test
    void requiresCheckingBeforeSavings() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "SAVINGS", "BRL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKING_ACCOUNT_REQUIRED"));
    }

    @Test
    void createsSavingsAfterChecking() throws Exception {
        UUID customerId = UUID.randomUUID();
        createChecking(customerId, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "SAVINGS", "BRL")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountType").value("SAVINGS"));

        org.assertj.core.api.Assertions.assertThat(accountRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsThirdAccountAfterCustomerHasBothSupportedTypes() throws Exception {
        UUID customerId = UUID.randomUUID();
        createChecking(customerId, UUID.randomUUID());
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "SAVINGS", "BRL")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "BRL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_TYPE_ALREADY_EXISTS"));

        org.assertj.core.api.Assertions.assertThat(accountRepository.count()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(creationRequestRepository.count()).isEqualTo(2);
    }

    @Test
    void returnsBadRequestForMissingHeaderOrInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "CHECKING", "BRL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "INVESTMENT", "BRL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneAccount() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String payload = request(customerId, "CHECKING", "BRL");

        List<MvcResult> results = runConcurrently(
                () -> performCreation(key, payload),
                () -> performCreation(key, payload)
        );

        org.assertj.core.api.Assertions.assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 201);
        org.assertj.core.api.Assertions.assertThat(results)
                .extracting(result -> result.getResponse().getHeader("Idempotency-Replayed"))
                .containsExactlyInAnyOrder("false", "true");
        org.assertj.core.api.Assertions.assertThat(results.stream()
                        .map(result -> (String) com.jayway.jsonpath.JsonPath.read(
                                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
                                "$.accountId"))
                        .distinct())
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(accountRepository.count()).isOne();
    }

    @Test
    void concurrentRequestsWithDifferentKeysForSameTypeCreateOneAccount() throws Exception {
        UUID customerId = UUID.randomUUID();
        String payload = request(customerId, "CHECKING", "BRL");

        List<MvcResult> results = runConcurrently(
                () -> performCreation(UUID.randomUUID(), payload),
                () -> performCreation(UUID.randomUUID(), payload)
        );

        org.assertj.core.api.Assertions.assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat((String) com.jayway.jsonpath.JsonPath.read(
                        conflict.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("ACCOUNT_TYPE_ALREADY_EXISTS");
        org.assertj.core.api.Assertions.assertThat(accountRepository.count()).isOne();
        org.assertj.core.api.Assertions.assertThat(creationRequestRepository.count()).isOne();
    }

    private void createChecking(UUID customerId, UUID key) throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "BRL")))
                .andExpect(status().isCreated());
    }

    private static String request(UUID customerId, String accountType, String currency) {
        return """
                {
                  "customerId": "%s",
                  "accountType": "%s",
                  "currency": "%s"
                }
                """.formatted(customerId, accountType, currency);
    }

    private MvcResult performCreation(UUID key, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private List<MvcResult> runConcurrently(
            Callable<MvcResult> firstRequest,
            Callable<MvcResult> secondRequest
    ) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return firstRequest.call();
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return secondRequest.call();
            });
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
