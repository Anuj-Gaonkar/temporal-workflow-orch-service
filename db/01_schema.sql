-- temporal-workflow-orch-service - schema DDL
--
-- Shared database "cern_v2" (docker compose in the parent folder), own schema ("orch"). The service
-- only READS this table; it never creates, inserts or updates anything. Temporal's own history
-- lives in a separate Postgres container, never here.
--
-- Run by hand (scripts/load-data.sh does this and the next file): 
--   docker exec -i cern-v2-postgres psql -U cern -d cern_v2 < db/01_schema.sql
--
-- Idempotent to re-run - every statement is guarded by IF NOT EXISTS.

CREATE SCHEMA IF NOT EXISTS orch;

CREATE TABLE IF NOT EXISTS orch.customers (
    customer_id     VARCHAR(50)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    -- WhatsApp number as WhatsApp's API wants it: country code + number, digits only (e.g. 910000000001).
    wa_id           VARCHAR(20)  NOT NULL,
    initial_channel VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Set by another system when the customer keeps the required balance; a workflow's contactGuard reads it before each reminder.
    balance_maintained BOOLEAN   NOT NULL DEFAULT false,
    CONSTRAINT customers_pkey PRIMARY KEY (customer_id),
    CONSTRAINT customers_initial_channel_check CHECK (initial_channel IN ('WHATSAPP', 'VOICE')),
    CONSTRAINT customers_wa_id_digits_check CHECK (wa_id ~ '^[0-9]{8,20}$')
);

-- For a table created before this column existed (the CREATE above does nothing when the table is already there).
ALTER TABLE orch.customers ADD COLUMN IF NOT EXISTS balance_maintained BOOLEAN NOT NULL DEFAULT false;
