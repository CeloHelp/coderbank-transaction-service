package com.coderbank.coderbank_transaction_service;

import com.coderbank.coderbank_transaction_service.dto.request.AccountRequestDTO;
import com.coderbank.coderbank_transaction_service.dto.response.AccountCreationResult;
import com.coderbank.coderbank_transaction_service.model.Account;
import com.coderbank.coderbank_transaction_service.model.AccountCreationRequest;
import com.coderbank.coderbank_transaction_service.model.AccountType;
import com.coderbank.coderbank_transaction_service.model.CurrencyType;
import com.coderbank.coderbank_transaction_service.repository.AccountCreationRequestRepository;
import com.coderbank.coderbank_transaction_service.repository.AccountRepository;
import com.coderbank.coderbank_transaction_service.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
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

    @MockitoSpyBean
    private AccountRepository accountRepository;

    @MockitoSpyBean
    private AccountCreationRequestRepository creationRequestRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        creationRequestRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @AfterEach
    void resetRepositorySpies() {
        reset(accountRepository, creationRequestRepository);
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
    void rejectsReusedKeyWhenOnlyAccountTypeChanges() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        createChecking(customerId, key);

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "SAVINGS", "BRL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsReusedKeyWhenOnlyCurrencyChanges() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        createChecking(customerId, key);

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "USD")))
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
    void returnsBadRequestForMalformedIdempotencyKey() throws Exception {
        assertValidationError(post("/api/v1/accounts")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(UUID.randomUUID(), "CHECKING", "BRL")));
    }

    @Test
    void returnsBadRequestForMalformedCustomerId() throws Exception {
        assertValidationError(post("/api/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("not-a-uuid", "CHECKING", "BRL")));
    }

    @Test
    void returnsBadRequestForUnknownCurrency() throws Exception {
        assertValidationError(post("/api/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(UUID.randomUUID().toString(), "CHECKING", "GBP")));
    }

    @Test
    void returnsBadRequestForMissingBody() throws Exception {
        assertValidationError(post("/api/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void returnsBadRequestForIncorrectContentType() throws Exception {
        assertValidationError(post("/api/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.TEXT_PLAIN)
                .content(request(UUID.randomUUID(), "CHECKING", "BRL")));
    }

    @Test
    void returnsBadRequestForUnknownJsonProperty() throws Exception {
        String payload = """
                {
                  "customerId": "%s",
                  "accountType": "CHECKING",
                  "currency": "BRL",
                  "amount": 100
                }
                """.formatted(UUID.randomUUID());

        assertValidationError(post("/api/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    @Test
    void returnsSafeInternalErrorForIncompleteIdempotencyRequest() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> creationRequestRepository.reserve(
                key, customerId, "CHECKING", "BRL"));

        String response = mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "BRL")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("constraint")
                .doesNotContainIgnoringCase("stack trace")
                .doesNotContain("account_creation_requests");
    }

    @Test
    void sameKeyRequestWaitsForOwnerTransactionAndReplaysItsAccount() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        AccountRequestDTO request = new AccountRequestDTO(customerId, AccountType.CHECKING, CurrencyType.BRL);
        CountDownLatch ownerReserved = new CountDownLatch(1);
        CountDownLatch allowOwnerCommit = new CountDownLatch(1);
        CountDownLatch replayAttemptedReservation = new CountDownLatch(1);
        AtomicInteger reservationCalls = new AtomicInteger();

        doAnswer(invocation -> {
            if (reservationCalls.incrementAndGet() == 2) {
                replayAttemptedReservation.countDown();
            }
            UUID reservationKey = invocation.getArgument(0, UUID.class);
            UUID reservationCustomerId = invocation.getArgument(1, UUID.class);
            String reservationAccountType = invocation.getArgument(2, String.class);
            String reservationCurrency = invocation.getArgument(3, String.class);
            return jdbcTemplate.update("""
                    INSERT INTO account_creation_requests (
                        idempotency_key, customer_id, account_type, currency
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """,
                    reservationKey,
                    reservationCustomerId,
                    reservationAccountType,
                    reservationCurrency);
        }).when(creationRequestRepository).reserve(any(), any(), any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> owner = executor.submit(() -> transactionTemplate.execute(status -> {
                assertThat(creationRequestRepository.reserve(key, customerId, "CHECKING", "BRL")).isOne();
                Account account = accountRepository.saveAndFlush(
                        Account.open(customerId, AccountType.CHECKING, CurrencyType.BRL));
                AccountCreationRequest reservation = creationRequestRepository.findById(key).orElseThrow();
                reservation.complete(account.getId());
                creationRequestRepository.saveAndFlush(reservation);
                ownerReserved.countDown();
                awaitLatch(allowOwnerCommit, "owner commit was not released");
                return account.getId();
            }));

            awaitLatch(ownerReserved, "owner did not reserve the idempotency key");
            Future<AccountCreationResult> replay = executor.submit(() -> accountService.createAccount(key, request));
            awaitLatch(replayAttemptedReservation, "replay did not reach the reservation");
            assertThatThrownBy(() -> replay.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowOwnerCommit.countDown();
            UUID accountId = owner.get(5, TimeUnit.SECONDS);
            AccountCreationResult replayResult = replay.get(5, TimeUnit.SECONDS);

            assertThat(replayResult.replayed()).isTrue();
            assertThat(replayResult.account().accountId()).isEqualTo(accountId);
            assertThat(accountRepository.count()).isOne();
            assertThat(creationRequestRepository.count()).isOne();
        } finally {
            allowOwnerCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsWithDifferentKeysForSameTypeCreateOneAccount() throws Exception {
        UUID customerId = UUID.randomUUID();
        String payload = request(customerId, "CHECKING", "BRL");
        CyclicBarrier preventiveCheckBarrier = new CyclicBarrier(2);

        doAnswer(invocation -> {
            awaitBarrier(preventiveCheckBarrier);
            return jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM accounts WHERE customer_id = ? AND account_type = ?
                    )
                    """, Boolean.class, customerId, AccountType.CHECKING.name());
        }).when(accountRepository).existsByCustomerIdAndAccountType(customerId, AccountType.CHECKING);

        List<MvcResult> results = runConcurrently(
                () -> performCreation(UUID.randomUUID(), payload),
                () -> performCreation(UUID.randomUUID(), payload)
        );

        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(
                        conflict.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("ACCOUNT_TYPE_ALREADY_EXISTS");
        assertThat(accountRepository.count()).isOne();
        assertThat(creationRequestRepository.count()).isOne();
    }

    private void createChecking(UUID customerId, UUID key) throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(customerId, "CHECKING", "BRL")))
                .andExpect(status().isCreated());
    }

    private static String request(UUID customerId, String accountType, String currency) {
        return request(customerId.toString(), accountType, currency);
    }

    private static String request(String customerId, String accountType, String currency) {
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
                awaitLatch(start, "concurrent requests were not released");
                return firstRequest.call();
            });
            Future<MvcResult> second = executor.submit(() -> {
                awaitLatch(start, "concurrent requests were not released");
                return secondRequest.call();
            });
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertValidationError(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requestBuilder
    ) throws Exception {
        mockMvc.perform(requestBuilder)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static void awaitLatch(CountDownLatch latch, String failureMessage) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as(failureMessage).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, exception);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
    }
}
