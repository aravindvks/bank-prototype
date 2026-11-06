// Thin client for the BFF (/v1) and, for the demo toolbar only, the vendor simulator (/sim).

export type Me = {
  customerId: string;
  customerType: string;
  status: string;
  kycStatus: string | null;
  riskRating: string | null;
  kycCaseId?: string;
  account?: {
    accountId: string;
    name: string | null;
    sortCode: string | null;
    accountNumber: string | null;
    available: string | null;
    ledger?: string;
    balanceError?: string;
  };
  card?: {
    token: string; last4: string | null; status: string; dailyLimit: string;
    online: boolean; contactless: boolean; atm: boolean;
  };
};

export type Payment = {
  paymentId: string;
  correlationId: string;
  debitAccount: string;
  creditor: string; // JSON text
  amount: string;
  currency: string;
  paymentType: string;
  status: string;
  finastraRef: string | null;
  createdAt: string;
  history?: { from: string | null; to: string; reason: string | null; actor: string; at: string }[];
};

export type Onboarding = {
  onboardingId: string;
  status: string;
  step: string;
  customerId: string | null;
  accountId: string | null;
  sortCode: string | null;
  accountNumber: string | null;
  cardLast4: string | null;
  reason: string | null;
};

export type CardControls = { frozen: boolean; dailyLimit: string; online: boolean; contactless: boolean; atm: boolean };

export class ApiError extends Error {
  constructor(message: string, readonly code?: string, readonly correlationId?: string) {
    super(message);
  }
}

/** Set by main.tsx after Keycloak sign-in; empty when login is off (local Vite runs). */
export const auth: {
  enabled: boolean;
  token: () => Promise<string | undefined>;
  username: string;
  roles: string[];
  logout: () => void;
} = { enabled: false, token: async () => undefined, username: '', roles: [], logout: () => {} };

/** Authorization header when signed in; otherwise the development headers. */
export async function authHeaders(dev: Record<string, string | undefined> = {}): Promise<Record<string, string>> {
  const t = await auth.token();
  if (t) return { Authorization: `Bearer ${t}` };
  return Object.fromEntries(Object.entries(dev).filter(([, v]) => v)) as Record<string, string>;
}

async function request<T>(path: string, init: RequestInit = {}, customerId?: string): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (!path.startsWith('/sim/')) Object.assign(headers, await authHeaders({ 'X-Customer-Id': customerId }));
  if (init.method === 'POST') headers['Idempotency-Key'] = crypto.randomUUID();
  const res = await fetch(path, { ...init, headers: { ...headers, ...(init.headers as Record<string, string>) } });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new ApiError(body?.detail ?? `Request failed (${res.status})`, body?.code, body?.correlationId);
  }
  return body as T;
}

export const api = {
  me: (customerId?: string) => request<Me>('/v1/me', {}, customerId),
  payments: (customerId: string) => request<Payment[]>('/v1/payments', {}, customerId),
  payment: (paymentId: string) => request<Payment>(`/v1/payments/${paymentId}`),
  pay: (customerId: string, body: unknown) =>
    request<{ paymentId: string; correlationId: string }>('/v1/payments', { method: 'POST', body: JSON.stringify(body) }, customerId),
  signUp: (body: unknown) =>
    request<{ onboardingId: string; correlationId: string }>('/v1/onboarding', { method: 'POST', body: JSON.stringify(body) }),
  onboarding: (id: string) => request<Onboarding>(`/v1/onboarding/${id}`),
  cardControls: (customerId: string, body: CardControls) =>
    request<{ commandId: string; correlationId: string }>('/v1/cards/controls', { method: 'PUT', body: JSON.stringify(body) }, customerId),
};

// Demo toolbar: plays the card network, other banks and Atfinity through the vendor simulator.
export const demo = {
  tap: (token: string, amount: string, merchant: string, channel: string) =>
    request<{ messageId: string; decision: string; reason: string | null; latencyMs: number }>(
      '/sim/thredd/v1/sim/card-tap', { method: 'POST', body: JSON.stringify({ token, amount, merchant, channel }) }),
  receive: (accountId: string, amount: string) =>
    request('/sim/essence/v1/sim/inbound', {
      method: 'POST',
      body: JSON.stringify({ creditAccountId: accountId, amount, debtorName: 'Arjun Mehta', reference: 'Demo transfer' }),
    }),
  sanctionsHit: (caseId: string) => request(`/sim/atfinity/v1/sim/cases/${caseId}/rescreen-hit`, { method: 'POST' }),
};

export const gbp = (v: string | null | undefined) =>
  v == null ? '—' : new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' }).format(Number(v));

/** Parses PostgreSQL timestamp text ("2026-11-06 13:55:02.1+00") as well as ISO 8601. */
export const toDate = (t: string) => new Date(t.replace(' ', 'T').replace(/([+-]\d\d)$/, '$1:00'));
