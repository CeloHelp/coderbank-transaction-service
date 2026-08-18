DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM accounts
        WHERE NOT pg_input_is_valid(customer_id, 'uuid')
    ) THEN
        RAISE EXCEPTION 'Migration aborted: accounts.customer_id contains invalid UUID values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM accounts
        GROUP BY customer_id::uuid
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Migration aborted: duplicate accounts exist for a customer';
    END IF;
END $$;

ALTER TABLE accounts
    ALTER COLUMN customer_id TYPE UUID USING customer_id::uuid,
    ADD COLUMN account_type VARCHAR(20);

UPDATE accounts SET account_type = 'CHECKING';

ALTER TABLE accounts
    ALTER COLUMN account_type SET NOT NULL,
    ADD CONSTRAINT ck_accounts_account_type
        CHECK (account_type IN ('CHECKING', 'SAVINGS')),
    ADD CONSTRAINT ck_accounts_currency
        CHECK (currency IN ('BRL', 'USD', 'EUR')),
    ADD CONSTRAINT ck_accounts_balance
        CHECK (balance >= 0),
    ADD CONSTRAINT uq_accounts_customer_type
        UNIQUE (customer_id, account_type);

CREATE TABLE account_creation_requests (
    idempotency_key UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    account_id UUID UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_creation_account_type
        CHECK (account_type IN ('CHECKING', 'SAVINGS')),
    CONSTRAINT ck_account_creation_currency
        CHECK (currency IN ('BRL', 'USD', 'EUR')),
    CONSTRAINT fk_account_creation_account
        FOREIGN KEY (account_id) REFERENCES accounts(id)
);
