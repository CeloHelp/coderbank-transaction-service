CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          customer_id VARCHAR(255) NOT NULL,
                          balance DECIMAL(19, 2) NOT NULL,
                          currency VARCHAR(10) NOT NULL,
                          created_at TIMESTAMP NOT NULL
);