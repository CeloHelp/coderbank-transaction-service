# Implementação de tipos de conta e idempotência

## Escopo

Esta implementação foi realizada na branch `task-2-apply-idempotency` e cobre exclusivamente o `coderbank-transaction-service`.

As alterações coordenadas no `customer-service`, descritas no plano original, foram intencionalmente excluídas desta entrega porque pertencem a outro repositório.

## Contrato entregue

O endpoint de abertura de conta continua sendo:

```http
POST /api/v1/accounts
Idempotency-Key: <UUID>
Content-Type: application/json
```

O corpo aceito agora contém somente:

```json
{
  "customerId": "6f61d75d-a7f1-495d-a52f-6170d080ee88",
  "accountType": "CHECKING",
  "currency": "BRL"
}
```

O saldo inicial não pode mais ser informado pelo consumidor. Toda conta é aberta com saldo zero no servidor.

As respostas bem-sucedidas usam HTTP 201 e incluem o header:

```text
Idempotency-Replayed: false
```

ou, quando a criação anterior é reproduzida:

```text
Idempotency-Replayed: true
```

## Regras implementadas

- Foram adicionados os tipos de conta `CHECKING` e `SAVINGS`.
- Cada cliente pode possuir no máximo uma conta de cada tipo.
- Uma conta `SAVINGS` exige que o mesmo cliente já possua uma conta `CHECKING` confirmada.
- Toda conta inicia com saldo zero.
- A mesma chave com o mesmo payload retorna a conta original.
- A mesma chave com payload diferente retorna HTTP 409 e código `IDEMPOTENCY_KEY_REUSED`.
- Outra chave tentando criar um tipo existente retorna HTTP 409 e código `ACCOUNT_TYPE_ALREADY_EXISTS`.
- Uma poupança sem conta corrente retorna HTTP 409 e código `CHECKING_ACCOUNT_REQUIRED`.
- Header ausente, UUID malformado, campos ausentes e enums inválidos retornam HTTP 400 e código `VALIDATION_ERROR`.
- Propriedades JSON desconhecidas retornam HTTP 400 e código `VALIDATION_ERROR`.

## Banco de dados

Foi criada a migration:

```text
src/main/resources/db/migration/V2__add_account_type_and_idempotency.sql
```

A migration realiza as seguintes operações:

1. Valida os valores legados de `accounts.customer_id` usando o parser nativo `pg_input_is_valid(..., 'uuid')` do PostgreSQL.
2. Interrompe explicitamente a execução quando existem IDs inválidos.
3. Interrompe explicitamente a execução quando existem contas legadas duplicadas pela representação UUID canônica do cliente.
4. Converte `customer_id` de `VARCHAR` para `UUID`.
5. Adiciona `account_type` e classifica contas legadas como `CHECKING`.
6. Adiciona checks para tipo de conta, moeda e saldo não negativo.
7. Cria a constraint `uq_accounts_customer_type`.
8. Cria `account_creation_requests` com chave idempotente, payload original, referência da conta e timestamp.
9. Adiciona checks de tipo e moeda também na tabela de idempotência.
10. Adiciona a FK nomeada `fk_account_creation_account`.

A configuração duplicada de conexão do Flyway foi removida de `application.yml`. O Flyway agora utiliza o mesmo datasource da aplicação, inclusive quando o datasource é substituído pelo Testcontainers.

A migration V1 publicada foi restaurada byte a byte a partir de `origin/main`. As regras de `.gitattributes` preservam LF para Java, SQL, YAML e Markdown, CRLF para scripts `.cmd` e a regra LF já existente para o Maven wrapper.

## Modelo e DTOs

Foi criado o enum `AccountType`.

A entidade `Account` foi alterada para:

- usar `UUID` em `customerId`;
- persistir `AccountType` como string;
- declarar nomes, nullability, precisão e escala das colunas relevantes;
- remover setters públicos de saldo e moeda;
- abrir contas somente pela factory `Account.open(...)`;
- definir `BigDecimal.ZERO` internamente.

O request deixou de aceitar `amount` e `description`.

O Jackson foi configurado explicitamente para rejeitar propriedades desconhecidas. O `customer_service` ainda precisa ser atualizado de forma coordenada antes da habilitação em ambiente compartilhado, pois o contrato antigo envia `amount` e `description` e não envia `accountType`.

O response agora contém:

- `accountId`;
- `customerId`;
- `accountType`;
- `balance`;
- `currency`;
- `createdAt`.

## Idempotência e transações

Foi criada a entidade `AccountCreationRequest` e seu repository.

A reserva da chave usa uma operação atômica do PostgreSQL:

```sql
INSERT INTO account_creation_requests (...)
VALUES (...)
ON CONFLICT (idempotency_key) DO NOTHING
```

O retorno da operação diferencia a requisição proprietária da chave de um replay.

Todo o fluxo de reserva, validação, criação da conta e associação de `account_id` ocorre em uma única transação por meio de `TransactionTemplate`.

Essa escolha difere da anotação `@Transactional` sugerida inicialmente. O wrapper programático permite que uma violação da unique constraint saia da transação, cause rollback e somente depois seja convertida em `AccountTypeAlreadyExistsException`. Assim, nenhuma consulta é executada em uma transação PostgreSQL abortada.

A tradução de concorrência verifica especificamente o nome `uq_accounts_customer_type`. Outras violações de integridade não são classificadas incorretamente como conta duplicada.

Se uma operação falhar, tanto a conta quanto a reserva idempotente são revertidas. Falhas não são armazenadas para replay.

Um registro idempotente confirmado sem `account_id` é tratado como estado interno inconsistente e retorna uma resposta genérica, sem expor detalhes do banco.

## Tratamento de erros

Foi adicionado um `GlobalExceptionHandler` com respostas estruturadas contendo:

```text
code
message
timestamp
```

Mapeamentos implementados:

| Situação | HTTP | Código |
|---|---:|---|
| Chave reutilizada com outro payload | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Tipo já existente | 409 | `ACCOUNT_TYPE_ALREADY_EXISTS` |
| Poupança sem corrente | 409 | `CHECKING_ACCOUNT_REQUIRED` |
| Request/header inválido | 400 | `VALIDATION_ERROR` |
| Registro idempotente inconsistente | 500 | `INTERNAL_ERROR` |

Mensagens internas e stack traces do PostgreSQL não são retornados pela API.

## Concorrência

Foram cobertos dois cenários concorrentes com coordenação determinística e timeouts explícitos:

- Mesma chave e mesmo payload: a transação proprietária mantém a reserva aberta enquanto a segunda alcança e aguarda o `INSERT ... ON CONFLICT`; após o commit, a segunda prossegue como replay do mesmo `accountId`.
- Chaves diferentes para o mesmo cliente e tipo: uma barreira garante que ambas ultrapassem a verificação preventiva antes do insert; uma cria a conta, a outra atinge `uq_accounts_customer_type`, traduz o conflito após rollback e não mantém sua reserva.

A verificação preventiva melhora a resposta comum, enquanto `uq_accounts_customer_type` permanece como garantia definitiva contra corrida.

## Testes adicionados

### Domínio

`AccountTest` verifica que a factory abre a conta com os dados solicitados e saldo zero.

### Serviço

`AccountServiceTest` cobre:

- chave reutilizada com payload diferente;
- tentativa de poupança sem corrente;
- replay da conta original.

### API e integração

`AccountCreationIntegrationTest` usa Spring Boot, MockMvc e PostgreSQL real para cobrir:

- primeira criação de `CHECKING`;
- replay e estabilidade do `accountId`;
- não duplicação de contas no replay;
- conflito de payload;
- conflito de tipo com outra chave;
- pré-requisito da poupança;
- criação de corrente seguida de poupança;
- rejeição de uma terceira conta;
- header ausente;
- payload incompleto;
- enum inválido;
- UUID malformado no header e no `customerId`;
- moeda desconhecida;
- body ausente e `Content-Type` incorreto;
- reutilização da chave alterando somente tipo ou moeda;
- propriedade JSON desconhecida;
- estado idempotente confirmado sem `account_id`, com erro interno seguro;
- concorrência com a mesma chave;
- concorrência com chaves diferentes.

### Migration

`AccountMigrationTest` executa V1 e V2 em schemas isolados do PostgreSQL 16 e verifica:

- migração de legado válido, incluindo UUIDv7;
- rejeição de UUID inválido;
- rejeição de contas legadas duplicadas;
- rejeição de grafias distintas do mesmo UUID, com rollback completo e schema mantido na V1.

A imagem de testes foi fixada em `postgres:16`, substituindo `postgres:latest`.

## Alterações em relação ao plano original

1. A branch utilizada é `task-2-apply-idempotency`, e não `task-2-account-idempotency`.
2. O escopo do `customer-service` foi excluído desta entrega.
3. A validação de UUID usa o parser do PostgreSQL em vez de regex, permitindo todas as representações válidas aceitas pelo tipo, inclusive UUIDv7.
4. As consultas de diagnóstico da migration foram transformadas em validações que interrompem explicitamente a migration.
5. A tabela idempotente recebeu checks adicionais para conta e moeda.
6. O caso de uso usa `TransactionTemplate` para garantir tradução de constraint após rollback.
7. O estado inesperado `account_id IS NULL` foi definido como erro interno seguro.
8. A configuração do Flyway foi unificada com o datasource principal.
9. Foram adicionados testes específicos de migração e concorrência além dos testes funcionais.
10. A V1 foi restaurada byte a byte e os line endings dos arquivos da branch foram normalizados.
11. Propriedades JSON desconhecidas passaram a ser rejeitadas explicitamente.

## Itens que permanecem fora do escopo

- Alterações no `customer-service`, Feign e política de retry.
- Transação distribuída entre os serviços.
- Outbox, saga ou estado `ACCOUNT_PENDING`.
- Regras de movimentação específicas da conta `SAVINGS`.
- Lock por cliente para coordenar uma abertura simultânea de `CHECKING` e `SAVINGS`.

## Backlog de status da conta

A regra atual considera qualquer conta `CHECKING` existente apta a permitir a abertura de `SAVINGS`. Quando bloqueio ou encerramento forem introduzidos, a validação deverá exigir explicitamente `accountType = CHECKING` e `status = ACTIVE`. `AccountStatus` não faz parte desta entrega.
