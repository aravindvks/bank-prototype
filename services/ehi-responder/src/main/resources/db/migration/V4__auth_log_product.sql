-- Credit cards (Thredd credit processing): which ledger holds the money for each authorisation.
-- DEBIT = Essence current account; CREDIT = Thredd credit account. Essence reconciliation reads DEBIT only.
ALTER TABLE auth_log ADD COLUMN product TEXT NOT NULL DEFAULT 'DEBIT';
