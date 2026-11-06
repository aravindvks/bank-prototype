-- Online banking logins (Identity Domains user → master customer ID).
CREATE TABLE customer_login (
  username    TEXT PRIMARY KEY,
  customer_id UUID NOT NULL,
  linked_by   TEXT NOT NULL,
  linked_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Who started each sign-up, so the login is linked to the customer when onboarding completes.
CREATE TABLE onboarding_owner (
  onboarding_id UUID PRIMARY KEY,
  username      TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
