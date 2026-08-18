# Plano: tipos de conta e idempotencia na abertura

## Objetivo

Evoluir o `transaction_service` para criar contas de forma idempotente e suportar os tipos `CHECKING` (conta corrente) e `SAVINGS` (conta poupanca), sem permitir mais de uma conta de cada tipo por cliente.

Este plano cobre apenas abertura e classificacao de contas. As regras de movimentacao da poupanca pertencem ao modulo de transacoes e devem ser implementadas em uma etapa posterior.

## Decisoes de dominio

- Um cliente pode possuir no maximo uma conta `CHECKING` e uma conta `SAVINGS`.
- A conta `CHECKING` e criada no onboarding do cliente.
- Uma conta `SAVINGS` somente pode ser criada quando o cliente ja possui uma `CHECKING` ativa.
- Toda conta inicia com saldo zero.
- O consumidor nao informa saldo inicial nem descricao na abertura.
- A moeda inicial continua sendo informada no contrato.
- A mesma `Idempotency-Key` com o mesmo payload retorna a conta criada anteriormente.
- A mesma `Idempotency-Key` com outro payload retorna HTTP 409.
- Uma chave diferente tentando criar um tipo ja existente para o cliente retorna HTTP 409.

## Contrato HTTP desejado

### Requisicao

```http
POST /api/v1/accounts
Idempotency-Key: 8141384c-8102-45df-8154-1c879ba177a8
Content-Type: application/json
```

```json
{
  "customerId": "6f61d75d-a7f1-495d-a52f-6170d080ee88",
  "accountType": "CHECKING",
  "currency": "BRL"
}
```

### Respostas

| Cenario | Status | Resultado |
|---|---:|---|
| Primeira criacao | 201 | Nova conta e `Idempotency-Replayed: false` |
| Mesma chave e payload | 201 | Mesma conta e `Idempotency-Replayed: true` |
| Mesma chave, payload diferente | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Mesmo cliente e tipo, outra chave | 409 | `ACCOUNT_TYPE_ALREADY_EXISTS` |
| `SAVINGS` sem `CHECKING` | 409 | `CHECKING_ACCOUNT_REQUIRED` |
| Header ausente ou payload invalido | 400 | Erro de validacao |

## Ordem de trabalho

### 1. Preparar a branch

1. Integrar `task-1-receive-connection` na `main`.
2. Atualizar a `main` local.
3. Criar `task-2-account-idempotency` a partir da `main` atualizada.
4. Preservar ou resolver antes da troca de branch a alteracao local existente em `src/main/resources/application.yml`.

### 2. Criar `AccountType`

Criar:

`src/main/java/com/coderbank/coderbank_transaction_service/model/AccountType.java`

```java
public enum AccountType {
    CHECKING,
    SAVINGS
}
```

### 3. Criar a migration V2

Criar:

`src/main/resources/db/migration/V2__add_account_type_and_idempotency.sql`

A migration deve:

1. Converter `accounts.customer_id` de `VARCHAR` para `UUID`, validando antes se os dados existentes sao UUIDs validos.
2. Adicionar `accounts.account_type` inicialmente como nullable.
3. Preencher contas existentes com `CHECKING`.
4. Tornar `account_type` obrigatorio.
5. Criar uma restricao de valores para `CHECKING` e `SAVINGS`.
6. Criar unicidade em `(customer_id, account_type)`.
7. Criar checks para moeda e saldo nao negativo.
8. Criar a tabela `account_creation_requests`.

Antes da unique constraint, verificar duplicidades legadas:

```sql
SELECT customer_id, COUNT(*)
FROM accounts
GROUP BY customer_id
HAVING COUNT(*) > 1;
```

Nao apagar contas automaticamente se houver duplicidade. Interromper a migration e sanear os dados conscientemente.

Estrutura sugerida:

```sql
CREATE TABLE account_creation_requests (
    idempotency_key UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    account_id UUID UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_account_creation_account
        FOREIGN KEY (account_id) REFERENCES accounts(id)
);
```

Usar nomes explicitos para as constraints, por exemplo:

```text
uq_accounts_customer_type
ck_accounts_account_type
ck_accounts_currency
ck_accounts_balance
fk_account_creation_account
```

### 4. Atualizar `Account`

Modificar:

`src/main/java/com/coderbank/coderbank_transaction_service/model/Account.java`

Alteracoes:

- Trocar `customerId` de `String` para `UUID`.
- Adicionar `AccountType accountType` com `@Enumerated(EnumType.STRING)`.
- Declarar nullability e nomes de coluna explicitamente.
- Declarar saldo como `precision = 19, scale = 2`.
- Remover setters que permitam alterar livremente moeda ou saldo, se nao forem necessarios.
- Fazer a factory de abertura definir `balance = BigDecimal.ZERO`.
- Remover imports e pontuacao nao utilizados.

Assinatura conceitual:

```java
public static Account open(
        UUID customerId,
        AccountType accountType,
        CurrencyType currency
) {
    return new Account(
            customerId,
            accountType,
            BigDecimal.ZERO,
            currency
    );
}
```

### 5. Simplificar os DTOs

Modificar:

`src/main/java/com/coderbank/coderbank_transaction_service/dto/request/AccountRequestDTO.java`

Contrato desejado:

```java
public record AccountRequestDTO(
        @NotNull UUID customerId,
        @NotNull AccountType accountType,
        @NotNull CurrencyType currency
) {
}
```

Remover `amount` e `description`. O saldo inicial deve ser zero por regra do servidor.

Modificar:

`src/main/java/com/coderbank/coderbank_transaction_service/dto/response/AccountResponseDTO.java`

Incluir pelo menos:

```text
accountId
customerId
accountType
balance
currency
createdAt
```

### 6. Criar a persistencia da idempotencia

Criar:

- `model/AccountCreationRequest.java`
- `repository/AccountCreationRequestRepository.java`

A entidade deve representar os campos da tabela `account_creation_requests`.

O repository precisa reservar a chave atomicamente com PostgreSQL:

```sql
INSERT INTO account_creation_requests (
    idempotency_key,
    customer_id,
    account_type,
    currency
)
VALUES (:key, :customerId, :accountType, :currency)
ON CONFLICT (idempotency_key) DO NOTHING;
```

O metodo deve retornar a quantidade de linhas inseridas:

```text
1 -> esta requisicao reservou a chave
0 -> a chave ja existe e deve ser analisada como replay ou conflito
```

Nao implementar apenas `findById` seguido de `save`, pois duas requisicoes concorrentes podem observar a chave como ausente.

### 7. Expandir `AccountRepository`

Modificar:

`src/main/java/com/coderbank/coderbank_transaction_service/repository/AccountRepository.java`

Adicionar consultas equivalentes a:

```java
Optional<Account> findByCustomerIdAndAccountType(
        UUID customerId,
        AccountType accountType
);

boolean existsByCustomerIdAndAccountType(
        UUID customerId,
        AccountType accountType
);
```

A consulta preventiva melhora a mensagem, mas a constraint `uq_accounts_customer_type` continua sendo a garantia contra concorrencia.

### 8. Implementar o caso de uso transacional

Modificar:

`src/main/java/com/coderbank/coderbank_transaction_service/service/AccountService.java`

O metodo de criacao deve receber a chave e ser `@Transactional`.

Fluxo:

```text
1. Reservar Idempotency-Key com INSERT ON CONFLICT.
2. Se a chave ja existe:
   - comparar customerId, accountType e currency;
   - payload diferente -> 409;
   - payload igual e accountId preenchido -> devolver a conta original.
3. Verificar se o cliente ja possui o tipo solicitado.
4. Para SAVINGS, verificar se existe CHECKING.
5. Criar a conta com saldo zero.
6. Fazer flush para avaliar constraints dentro do caso de uso.
7. Associar accountId ao registro idempotente.
8. Confirmar tudo na mesma transacao.
```

Nao capturar uma violacao de unique constraint e continuar consultando na mesma transacao. No PostgreSQL, a transacao fica abortada depois da violacao. Prefira evitar conflitos esperados com `ON CONFLICT` e traduza conflitos inesperados depois do rollback.

Para a primeira versao, uma criacao simultanea de `CHECKING` e `SAVINGS` pode fazer a poupanca retornar conflito enquanto a corrente ainda nao foi confirmada. Isso preserva a invariavel e pode ser refinado futuramente com lock por cliente.

### 9. Atualizar o controller

Modificar:

`src/main/java/com/coderbank/coderbank_transaction_service/controller/AccountController.java`

Responsabilidades:

- Receber `@RequestHeader("Idempotency-Key") UUID idempotencyKey`.
- Aplicar `@Valid` ao body.
- Chamar o service com chave e request.
- Retornar `Idempotency-Replayed`.
- Retornar 201 para criacao e replay bem-sucedido.

Pode ser criado um resultado interno para distinguir criacao de replay:

```java
public record AccountCreationResult(
        AccountResponseDTO account,
        boolean replayed
) {
}
```

### 10. Criar erros de dominio

Criar um pacote `exceptions` com:

- `IdempotencyKeyConflictException`
- `AccountTypeAlreadyExistsException`
- `CheckingAccountRequiredException`
- `ApiErrorResponse`
- `GlobalExceptionHandler`

Mapeamentos:

```text
IdempotencyKeyConflictException -> 409 IDEMPOTENCY_KEY_REUSED
AccountTypeAlreadyExistsException -> 409 ACCOUNT_TYPE_ALREADY_EXISTS
CheckingAccountRequiredException -> 409 CHECKING_ACCOUNT_REQUIRED
Validacao/header ausente -> 400
```

Nao retornar stack trace ou mensagens internas do PostgreSQL.

### 11. Adicionar testes

Criar testes unitarios do service e testes de integracao com PostgreSQL real.

Casos obrigatorios:

1. Primeira `CHECKING` cria conta com saldo zero.
2. Mesma chave e mesmo payload retorna o mesmo `accountId`.
3. Replay nao aumenta a quantidade de contas.
4. Mesma chave com payload diferente retorna 409.
5. Chaves diferentes para o mesmo cliente/tipo retornam 409 na segunda tentativa.
6. `SAVINGS` sem `CHECKING` retorna 409.
7. `CHECKING` seguida de `SAVINGS` funciona.
8. Terceira conta de qualquer tipo suportado nao e criada.
9. Header ausente retorna 400.
10. Campos ausentes ou tipo invalido retornam 400.
11. Duas requisicoes concorrentes com a mesma chave criam uma unica conta.
12. Duas requisicoes concorrentes com chaves diferentes e mesmo cliente/tipo criam uma unica conta.

Usar Testcontainers/PostgreSQL para os testes de concorrencia e constraints. Fixar a imagem na mesma major usada localmente, preferencialmente `postgres:16`, em vez de `postgres:latest`.

## Alteracoes coordenadas no customer-service

Executar somente depois que o novo contrato do `transaction_service` estiver funcional.

### Arquivos

- `client/dtoclient/request/RequestClient.java`
- `client/dtoclient/response/ResponseClient.java`
- `client/CustomerInterface.java`
- `client/AccountGateway.java`
- `service/CustomerService.java`
- testes de `AccountGateway` e `CustomerService`

### Mudancas

1. Remover `amount` e `description` de `RequestClient`.
2. Adicionar `accountType` e enviar `CHECKING` no onboarding.
3. Adicionar `@RequestHeader("Idempotency-Key")` ao Feign.
4. Gerar a chave uma vez antes de chamar o gateway.
5. Propagar a mesma chave em todas as tentativas do retry.
6. Atualizar a assinatura do fallback com todos os argumentos originais e `Throwable` ao final.
7. Atualizar `ResponseClient` para moeda e tipo, se a resposta for consumida.
8. Depois da idempotencia, configurar o retry para falhas transitorias e impedir retry de respostas 4xx.

## Riscos que permanecem fora deste escopo

- A idempotencia evita contas duplicadas por retry, mas nao cria uma transacao distribuida entre os bancos dos dois servicos.
- Ainda pode existir uma conta remota se o `customer_service` sofrer rollback depois da criacao.
- A evolucao recomendada para esse caso e onboarding com estado `ACCOUNT_PENDING` e processamento por outbox/saga.
- As regras de movimentacao da poupanca ainda precisam ser implementadas no modulo de transacoes:
  - pode receber creditos de qualquer origem;
  - sua unica saida permitida e para a `CHECKING` do mesmo cliente;
  - nao permite pagamento, transferencia a terceiros ou saque direto.

## Definition of Done

- Migration V2 aplicada em PostgreSQL 16 limpo e com dados legados validos.
- Hibernate inicia com `ddl-auto: validate`.
- Contrato aceita apenas cliente, tipo e moeda.
- Saldo inicial sempre e zero no servidor.
- Uma unica CC e CP por cliente, garantidas pelo banco.
- CP nao e criada sem CC.
- Replay retorna o mesmo `accountId`.
- Chave com payload diferente retorna 409.
- Testes unitarios, integracao e concorrencia passam.
- O `customer_service` envia `CHECKING` e reutiliza a mesma chave no retry.
- Respostas 4xx nao sao repetidas pelo retry.
