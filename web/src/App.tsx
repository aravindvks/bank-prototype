import { useCallback, useEffect, useState } from 'react';
import { api, demo, gbp, ApiError, type CardControls, type Me, type Onboarding, type Payment } from './api';
import Staff from './Staff';

const STORAGE_KEY = 'pbp.customerId';
const ONBOARDING_STEPS = [
  ['CUSTOMER_CREATED', 'Customer record created'],
  ['WAITING_FOR_KYC', 'Identity check with Atfinity'],
  ['KYC_APPROVED', 'Identity check approved'],
  ['ACCOUNT_OPENED', 'Current account opened in Essence'],
  ['CARD_ISSUED', 'Debit card issued by Thredd'],
  ['ACTIVE', 'Account ready'],
] as const;

type Tab = 'home' | 'payments' | 'card';

function readCustomer(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function saveCustomer(id: string | null) {
  try {
    if (id) localStorage.setItem(STORAGE_KEY, id);
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* storage unavailable: the session still works */
  }
}

export default function App() {
  const [customerId, setCustomerId] = useState<string | null>(readCustomer);
  const [toast, setToast] = useState<string | null>(null);
  const [staffView, setStaffView] = useState(() => location.hash === '#staff');

  useEffect(() => {
    const onHash = () => setStaffView(location.hash === '#staff');
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 4500);
    return () => clearTimeout(t);
  }, [toast]);

  const signIn = (id: string) => {
    saveCustomer(id);
    setCustomerId(id);
  };

  return (
    <>
      {staffView ? <Staff notify={setToast} /> : customerId ? (
        <Bank customerId={customerId} notify={setToast} onSignOut={() => { saveCustomer(null); setCustomerId(null); }} />
      ) : (
        <Welcome onReady={signIn} notify={setToast} />
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  );
}

// ---------------------------------------------------------------------------------------------
// Sign-up and onboarding progress
// ---------------------------------------------------------------------------------------------

function Welcome({ onReady, notify }: { onReady: (id: string) => void; notify: (m: string) => void }) {
  const [form, setForm] = useState({ name: 'Priya Shah', email: 'priya@example.com', customerType: 'INDIVIDUAL' });
  const [onboardingId, setOnboardingId] = useState<string | null>(null);
  const [existing, setExisting] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await api.signUp(form);
      setOnboardingId(r.onboardingId);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Sign-up failed');
    } finally {
      setBusy(false);
    }
  };

  if (onboardingId) return <OnboardingProgress id={onboardingId} onReady={onReady} onRestart={() => setOnboardingId(null)} />;

  return (
    <main className="shell narrow">
      <header className="brand">
        <h1>Banking Platform Prototype</h1>
        <p className="muted">UK current account · runs on the real backend with vendor simulators · <a href="#staff">Staff portal</a></p>
      </header>
      <form className="panel stack" onSubmit={submit}>
        <h2>Open an account</h2>
        <p className="muted">Names containing “Reject” or “Refer” trigger those KYC outcomes in the Atfinity simulator.</p>
        <label className="field">Full name
          <input id="name" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>
        <label className="field">Email
          <input id="email" type="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </label>
        <label className="field">Account type
          <select id="type" value={form.customerType} onChange={(e) => setForm({ ...form, customerType: e.target.value })}>
            <option value="INDIVIDUAL">Personal</option>
            <option value="BUSINESS">Business</option>
          </select>
        </label>
        <button className="btn primary" disabled={busy}>{busy ? 'Starting…' : 'Open account'}</button>
      </form>
      <form className="panel stack" onSubmit={(e) => { e.preventDefault(); if (existing.trim()) onReady(existing.trim()); }}>
        <h2>Already a customer?</h2>
        <label className="field">Customer ID
          <input id="existing" placeholder="0192f1c4-…" value={existing} onChange={(e) => setExisting(e.target.value)} />
        </label>
        <button className="btn">Continue</button>
      </form>
    </main>
  );
}

function OnboardingProgress({ id, onReady, onRestart }: { id: string; onReady: (c: string) => void; onRestart: () => void }) {
  const [state, setState] = useState<Onboarding | null>(null);

  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const s = await api.onboarding(id);
        if (!stop) setState(s);
        if (['COMPLETED', 'REJECTED', 'FAILED'].includes(s.status)) return;
      } catch {
        /* the saga may not have started yet */
      }
      if (!stop) setTimeout(tick, 1200);
    };
    tick();
    return () => { stop = true; };
  }, [id]);

  const reached = ONBOARDING_STEPS.findIndex(([s]) => s === state?.step);
  const done = state?.status === 'COMPLETED';
  return (
    <main className="shell narrow">
      <section className="panel stack">
        <p className="eyebrow">Opening your account</p>
        <h2>{done ? 'Your account is ready' : state?.status === 'REJECTED' ? 'We could not open your account' : 'Setting things up…'}</h2>
        <ol className="steps">
          {ONBOARDING_STEPS.map(([step, label], i) => (
            <li key={step} className={i <= reached ? 'done' : i === reached + 1 && !state?.reason ? 'now' : ''}>{label}</li>
          ))}
        </ol>
        {state?.reason && <p className="alert bad">{state.reason}</p>}
        {done && state?.customerId && (
          <>
            <dl className="kv">
              <div><dt>Sort code</dt><dd className="num">{fmtSort(state.sortCode)}</dd></div>
              <div><dt>Account number</dt><dd className="num">{state.accountNumber}</dd></div>
              <div><dt>Card</dt><dd>•••• {state.cardLast4}</dd></div>
            </dl>
            <button className="btn primary" onClick={() => onReady(state.customerId!)}>Go to my account</button>
          </>
        )}
        {state && state.status !== 'COMPLETED' && state.reason && <button className="btn" onClick={onRestart}>Start again</button>}
        <p className="muted mono small">Onboarding {id}</p>
      </section>
    </main>
  );
}

// ---------------------------------------------------------------------------------------------
// Signed-in bank
// ---------------------------------------------------------------------------------------------

function Bank({ customerId, notify, onSignOut }: { customerId: string; notify: (m: string) => void; onSignOut: () => void }) {
  const [tab, setTab] = useState<Tab>('home');
  const [me, setMe] = useState<Me | null>(null);
  const [payments, setPayments] = useState<Payment[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [m, p] = await Promise.all([api.me(customerId), api.payments(customerId)]);
      setMe(m);
      setPayments(p);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Cannot reach the bank');
    }
  }, [customerId]);

  // Live view: the BFF pushes this customer's events (Server-Sent Events); a slow poll is the fallback.
  const [live, setLive] = useState(false);
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 15000);
    const source = new EventSource(`/v1/events?customerId=${encodeURIComponent(customerId)}`);
    source.addEventListener('ready', () => setLive(true));
    source.addEventListener('bank', (ev) => {
      try {
        const e = JSON.parse((ev as MessageEvent).data) as { type: string; data: Record<string, unknown> };
        const text = describe(e.type, e.data);
        if (text) notify(text);
      } catch {
        /* ignore malformed events */
      }
      refresh();
    });
    source.onerror = () => setLive(false);
    return () => { clearInterval(t); source.close(); };
  }, [refresh, customerId, notify]);

  const frozen = me && me.status !== 'ACTIVE';
  return (
    <>
      <DemoBar me={me} notify={notify} after={refresh} />
      <main className="shell">
        <header className="topbar">
          <div className="brand">
            <h1>Banking Platform Prototype</h1>
            <p className="muted">{me?.account?.name ?? 'Loading…'} · <span className="mono small">{customerId}</span>
              {' '}<span className={`pill ${live ? 'p-ok' : 'p-n'}`}>{live ? 'live' : 'offline'}</span></p>
          </div>
          <div className="row">
            <a className="btn small" href="#staff">Staff portal</a>
            <button className="btn small" onClick={onSignOut}>Sign out</button>
          </div>
        </header>
        <nav className="tabs" aria-label="Sections">
          {(['home', 'payments', 'card'] as Tab[]).map((t) => (
            <button key={t} aria-current={tab === t ? 'page' : undefined} onClick={() => { setTab(t); setSelected(null); }}>
              {t === 'home' ? 'Home' : t === 'payments' ? 'Payments' : 'Card'}
            </button>
          ))}
        </nav>
        {error && <p className="alert bad">{error}</p>}
        {frozen && <p className="alert bad"><strong>Account under review.</strong> Card payments and transfers are paused while the bank completes a compliance check.</p>}
        {tab === 'home' && me && <Home me={me} payments={payments} open={(id) => { setTab('payments'); setSelected(id); }} />}
        {tab === 'payments' && me && (
          selected
            ? <PaymentDetail id={selected} back={() => setSelected(null)} />
            : <Payments me={me} customerId={customerId} payments={payments} open={setSelected} notify={notify} after={refresh} />
        )}
        {tab === 'card' && me && <CardView me={me} customerId={customerId} notify={notify} after={refresh} />}
      </main>
    </>
  );
}

function Home({ me, payments, open }: { me: Me; payments: Payment[]; open: (id: string) => void }) {
  return (
    <>
      <section className="panel stack">
        <p className="eyebrow">{me.customerType === 'BUSINESS' ? 'Business current account' : 'Current account'}</p>
        <div className="balance num">{gbp(me.account?.available)}</div>
        <p className="muted">
          Available{me.account?.ledger && me.account.ledger !== me.account.available ? ` · ${gbp(me.account.ledger)} including pending card payments` : ''}
          {me.account?.balanceError ? ` · ${me.account.balanceError}` : ''}
        </p>
        <dl className="kv">
          <div><dt>Sort code</dt><dd className="num">{fmtSort(me.account?.sortCode)}</dd></div>
          <div><dt>Account number</dt><dd className="num">{me.account?.accountNumber ?? '—'}</dd></div>
          <div><dt>Status</dt><dd><Pill status={me.status} /></dd></div>
          <div><dt>Identity check</dt><dd>{me.kycStatus ?? '—'}{me.riskRating ? ` · risk ${me.riskRating.toLowerCase()}` : ''}</dd></div>
        </dl>
      </section>
      <section className="panel stack">
        <h2>Recent payments</h2>
        <PaymentList payments={payments.slice(0, 5)} open={open} />
      </section>
    </>
  );
}

function Payments({ me, customerId, payments, open, notify, after }: {
  me: Me; customerId: string; payments: Payment[]; open: (id: string) => void; notify: (m: string) => void; after: () => void;
}) {
  const [form, setForm] = useState({ name: 'Arjun Mehta', sortCode: '040004', accountNumber: '12345678', amount: '15.00', reference: 'Dinner' });
  const [busy, setBusy] = useState(false);

  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!me.account) return;
    setBusy(true);
    try {
      const r = await api.pay(customerId, {
        debtorAccount: me.account.accountId,
        creditor: { name: form.name, sortCode: form.sortCode.replace(/\D/g, ''), accountNumber: form.accountNumber },
        amount: { value: Number(form.amount).toFixed(2), currency: 'GBP' },
        remittanceInfo: form.reference,
        copResult: 'MATCH',
      });
      notify(`Payment accepted · ${r.correlationId}`);
      after();
      open(r.paymentId);
    } catch (err) {
      notify(err instanceof ApiError ? `${err.code ?? 'Error'}: ${err.message}` : 'Payment failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <form className="panel stack" onSubmit={send}>
        <h2>Send money</h2>
        <p className="muted">Sort code 20-00-01 is this bank (instant transfer). Any other goes to Finastra, which routes it by Faster Payments; 99-xx-xx is rejected by the scheme.</p>
        <div className="form">
          <label className="field">Payee name<input id="payee" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label className="field">Sort code<input id="sort" required pattern="\d{2}-?\d{2}-?\d{2}" value={form.sortCode} onChange={(e) => setForm({ ...form, sortCode: e.target.value })} /></label>
          <label className="field">Account number<input id="acc" required pattern="\d{8}" value={form.accountNumber} onChange={(e) => setForm({ ...form, accountNumber: e.target.value })} /></label>
          <label className="field">Amount (£)<input id="amount" required inputMode="decimal" pattern="\d+(\.\d{1,2})?" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></label>
          <label className="field">Reference<input id="ref" maxLength={18} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} /></label>
        </div>
        <div><button className="btn primary" disabled={busy || me.status !== 'ACTIVE'}>{busy ? 'Sending…' : 'Send'}</button></div>
      </form>
      <section className="panel stack">
        <h2>All payments</h2>
        <PaymentList payments={payments} open={open} />
      </section>
    </>
  );
}

function PaymentList({ payments, open }: { payments: Payment[]; open: (id: string) => void }) {
  if (!payments.length) return <p className="muted">No payments yet. Send money, or use “Receive £20” in the demo bar.</p>;
  return (
    <div className="scroll">
      <table className="tx">
        <tbody>
          {payments.map((p) => {
            const inbound = p.paymentType === 'INBOUND';
            const c = parseCreditor(p.creditor);
            return (
              <tr key={p.paymentId} className="click" tabIndex={0} onClick={() => open(p.paymentId)} onKeyDown={(e) => e.key === 'Enter' && open(p.paymentId)}>
                <td>
                  <div>{inbound ? `From ${c.debtorName ?? 'another bank'}` : `To ${c.name ?? '—'}`}</div>
                  <div className="meta">{label(p.paymentType)} · {new Date(p.createdAt).toLocaleString('en-GB')}</div>
                </td>
                <td><Pill status={p.status} /></td>
                <td className={`amt num ${inbound ? 'in' : ''}`}>{inbound ? '+' : '−'}{gbp(p.amount)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PaymentDetail({ id, back }: { id: string; back: () => void }) {
  const [p, setP] = useState<Payment | null>(null);
  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const r = await api.payment(id);
        if (!stop) setP(r);
        if (['COMPLETED', 'REJECTED', 'FAILED', 'FLAGGED'].includes(r.status)) return;
      } catch {
        /* not recorded yet */
      }
      if (!stop) setTimeout(tick, 1000);
    };
    tick();
    return () => { stop = true; };
  }, [id]);

  const c = parseCreditor(p?.creditor);
  return (
    <section className="panel stack">
      <div><button className="btn small" onClick={back}>← All payments</button></div>
      {!p ? <p className="muted">Waiting for the payment to be recorded…</p> : (
        <>
          <div className="row between">
            <div>
              <p className="eyebrow">{label(p.paymentType)}</p>
              <h2>{p.paymentType === 'INBOUND' ? `From ${c.debtorName}` : `To ${c.name}`}</h2>
            </div>
            <div className="row"><span className="balance small num">{gbp(p.amount)}</span><Pill status={p.status} /></div>
          </div>
          <dl className="kv">
            <div><dt>Correlation ID</dt><dd className="mono small">{p.correlationId}</dd></div>
            <div><dt>Payment ID</dt><dd className="mono small">{p.paymentId}</dd></div>
            {p.finastraRef && <div><dt>Finastra reference</dt><dd className="mono small">{p.finastraRef}</dd></div>}
          </dl>
          <h3>Timeline</h3>
          <ol className="timeline">
            {p.history?.map((h, i) => (
              <li key={i} className={['REJECTED', 'FAILED', 'FLAGGED'].includes(h.to) ? 'bad' : ''}>
                <span className="meta">{new Date(h.at).toLocaleTimeString('en-GB')} · {h.actor}</span>
                <strong>{h.to}</strong>{h.reason ? ` — ${h.reason}` : ''}
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}

function CardView({ me, customerId, notify, after }: { me: Me; customerId: string; notify: (m: string) => void; after: () => void }) {
  const card = me.card;
  const [draft, setDraft] = useState<CardControls | null>(null);
  if (!card) return <section className="panel"><p className="muted">No card yet.</p></section>;
  const current: CardControls = draft ?? {
    frozen: card.status === 'BLOCKED', dailyLimit: card.dailyLimit, online: card.online, contactless: card.contactless, atm: card.atm,
  };
  const apply = async (next: CardControls) => {
    setDraft(next);
    try {
      const r = await api.cardControls(customerId, next);
      notify(`Card controls updated · ${r.correlationId}`);
      setTimeout(() => { setDraft(null); after(); }, 2500);
    } catch (err) {
      setDraft(null);
      notify(err instanceof ApiError ? err.message : 'Could not update the card');
    }
  };
  const blocked = me.status !== 'ACTIVE' || current.frozen;
  const toggle = (key: 'frozen' | 'online' | 'contactless' | 'atm', label: string) => (
    <div className="ctl">
      <span>{label}</span>
      <button className="sw" role="switch" aria-checked={current[key]} aria-label={label}
        onClick={() => apply({ ...current, [key]: !current[key] })} />
    </div>
  );
  return (
    <section className="panel stack">
      <div className={`plastic ${blocked ? 'frozen' : ''}`}>
        <div className="row between"><strong>Debit</strong><span className="mono">{blocked ? 'FROZEN' : 'VISA'}</span></div>
        <div className="pan">•••• •••• •••• {card.last4}</div>
        <div className="row between small"><span>{me.account?.name}</span><span className="mono">{card.token}</span></div>
      </div>
      <div>
        {toggle('frozen', 'Freeze card')}
        {toggle('online', 'Online payments')}
        {toggle('contactless', 'Contactless')}
        {toggle('atm', 'ATM withdrawals')}
        <div className="ctl">
          <span>Daily limit</span>
          <span className="seg">
            {['100.00', '300.00', '1000.00'].map((v) => (
              <button key={v} aria-pressed={current.dailyLimit === v} onClick={() => apply({ ...current, dailyLimit: v })}>
                {gbp(v).replace('.00', '')}
              </button>
            ))}
          </span>
        </div>
      </div>
      <p className="muted">Changes go to Thredd and the bank's card cache; the next tap uses them within about 5 seconds.
        The decision comes from the EHI responder: card and customer status, channel, daily limit, then an Essence hold.</p>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------
// Demo toolbar (dev and demo environments only, LLD section 16)
// ---------------------------------------------------------------------------------------------

function DemoBar({ me, notify, after }: { me: Me | null; notify: (m: string) => void; after: () => void }) {
  const [merchant, setMerchant] = useState('3.50|Bean There Cafe|contactless');
  const run = async (fn: () => Promise<string>) => {
    try {
      notify(await fn());
      setTimeout(after, 800);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Demo action failed');
    }
  };
  const [amount, name, channel] = merchant.split('|');
  return (
    <div className="band">
      <div className="band-inner">
        <span className="tag">Demo controls</span>
        <select id="merchant" aria-label="Merchant" value={merchant} onChange={(e) => setMerchant(e.target.value)}>
          <option value="3.50|Bean There Cafe|contactless">Cafe · £3.50 · contactless</option>
          <option value="45.00|Northwind Online|online">Online store · £45</option>
          <option value="100.00|ATM Liverpool St|atm">ATM · £100</option>
          <option value="400.00|Volt Electronics|chip">Electronics · £400 · chip</option>
        </select>
        <button className="bbtn primary" disabled={!me?.card} onClick={() => run(async () => {
          const d = await demo.tap(me!.card!.token, amount, name, channel);
          return d.decision === 'APPROVE' ? `Approved ${gbp(amount)} at ${name} (${d.latencyMs} ms)` : `Declined: ${d.reason}`;
        })}>Tap card</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.receive(me!.account!.accountId, '20.00');
          return 'Arjun sent you £20 by Faster Payment';
        })}>Receive £20</button>
        <button className="bbtn" disabled={!me?.kycCaseId} onClick={() => run(async () => {
          await demo.sanctionsHit(me!.kycCaseId!);
          return 'Atfinity rescreening raised a possible sanctions match';
        })}>Sanctions hit</button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------

function Pill({ status }: { status: string }) {
  const tone = ['COMPLETED', 'ACTIVE', 'VALID'].includes(status) ? 'ok'
    : ['REJECTED', 'FAILED', 'FROZEN', 'BLOCKED', 'FLAGGED'].includes(status) ? 'bad' : 'n';
  return <span className={`pill p-${tone}`}>{status.replace('_', ' ').toLowerCase()}</span>;
}

function parseCreditor(json?: string | null): Record<string, string | undefined> {
  try {
    return json ? JSON.parse(json) : {};
  } catch {
    return {};
  }
}

const label = (type: string) =>
  ({ INTERNAL: 'Same-bank transfer', FPS: 'Faster Payment', CHAPS: 'CHAPS', BACS: 'Bacs', INBOUND: 'Money in' } as Record<string, string>)[type] ?? type;

/** Short customer-facing text for a pushed event, or null to refresh silently. */
function describe(type: string, d: Record<string, unknown>): string | null {
  const amount = (d.amount as { value?: string } | undefined)?.value;
  switch (type) {
    case 'card.authorised': return `Card payment ${gbp(amount)} at ${d.merchant}`;
    case 'card.declined': return `Card declined at ${d.merchant}: ${d.reason}`;
    case 'payment.completed': return `Payment of ${gbp(amount)} completed`;
    case 'payment.rejected': return `Payment of ${gbp(amount)} rejected: ${d.reason}`;
    case 'payment.received': return d.status === 'FLAGGED' ? `${gbp(amount)} received and held for review` : `You received ${gbp(amount)} from ${d.debtorName}`;
    case 'payment.released': return `Held payment of ${gbp(amount)} released`;
    case 'customer.status_changed': return d.to === 'FROZEN' ? 'Your account is under review' : d.to === 'ACTIVE' ? 'Your account is fully available' : null;
    case 'card.controls_changed': return 'Card settings updated';
    default: return null;
  }
}

const fmtSort = (s?: string | null) => (s ? `${s.slice(0, 2)}-${s.slice(2, 4)}-${s.slice(4, 6)}` : '—');
