-- temporal-workflow-orch-service - default customers (the same six as the older orch service).
--
-- Run by hand AFTER db/01_schema.sql (scripts/load-data.sh does it). The service never runs this file.
--   docker exec -i cern-v2-postgres psql -U cern -d cern_v2 < db/02_default_customers.sql
--
-- Every customer gets the placeholder WhatsApp number 910000000001. Replace it locally with a real test number to reach a phone; do not commit it.
-- Idempotent: re-running changes nothing.

INSERT INTO orch.customers (customer_id, name, phone, wa_id, initial_channel) VALUES
    ('C1001', 'Test Customer One',   '+910000000001', '910000000001', 'VOICE'),
    ('C1002', 'Test Customer Two',   '+910000000001', '910000000001', 'WHATSAPP'),
    ('C1003', 'Test Customer Three', '+910000000001', '910000000001', 'VOICE'),
    ('C1004', 'Test Customer Four',  '+910000000001', '910000000001', 'WHATSAPP'),
    ('C1005', 'Test Customer Five',  '+910000000001', '910000000001', 'VOICE'),
    ('C1006', 'Test Customer Six',   '+910000000001', '910000000001', 'WHATSAPP')
ON CONFLICT (customer_id) DO NOTHING;
