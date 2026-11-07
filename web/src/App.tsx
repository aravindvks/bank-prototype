import { useCallback, useEffect, useState } from 'react';
import { api, auth, demo, gbp, toDate, ApiError, type CardControls, type Cop, type CreditCards, type Mandate, type Me, type Onboarding, type Payment, type Schedule } from './api';
import CreditCardView from './CreditCard';
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

type Tab = 'home' | 'payments' | 'card' | 'credit';

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
  const [customerId, setCustomerId] = useState<string | null>(() => (auth.enabled ? null : readCustomer()));
  // With login on, the bank customer comes from the signed-in user (CUS-LINK-001 = not linked yet → sign up).
  const [checking, setChecking] = useState(auth.enabled);
  useEffect(() => {
    if (!auth.enabled) return;
    api.me()
      .then((m) => setCustomerId(m.customerId))
      .catch(() => setCustomerId(null))
      .finally(() => setChecking(false));
  }, []);
  const [toast, setToast] = useState<string | null>(null);
  const isStaff = () => location.hash === '#staff' || location.pathname.startsWith('/staff');
  const [staffView, setStaffView] = useState(isStaff);

  useEffect(() => {
    const onHash = () => setStaffView(isStaff());
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
      {staffView ? <Staff notify={setToast} /> : checking ? (
        <main className="shell narrow"><p className="muted">Signing you in…</p></main>
      ) : customerId ? (
        <Bank customerId={customerId} notify={setToast}
          onSignOut={() => { saveCustomer(null); setCustomerId(null); if (auth.enabled) auth.logout(); }} />
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
  const [form, setForm] = useState({
    name: auth.enabled ? '' : 'Priya Shah',
    email: auth.enabled ? '' : 'priya@example.com',
    customerType: 'INDIVIDUAL',
  });
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
    <div className="welcome">
    <main className="shell narrow">
      <header className="stack">
        <span className="wordmark">Sutherland Banking Platform</span>
        <h1 className="intro">A UK current account, opened in a minute.</h1>
        <p className="intro-sub">Identity checked by Atfinity, account by Finastra, card by Thredd: all running on simulators. Staff work in the <a href="/staff">Control Room</a>.</p>
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
      {auth.enabled ? (
        <p className="muted small">Signed in as <strong>{auth.username}</strong>. This login is not linked to a bank account yet. <button className="linklike" onClick={auth.logout}>Sign out</button></p>
      ) : (
      <form className="panel stack" onSubmit={(e) => { e.preventDefault(); if (existing.trim()) onReady(existing.trim()); }}>
        <h2>Already a customer?</h2>
        <label className="field">Customer ID
          <input id="existing" placeholder="0192f1c4-…" value={existing} onChange={(e) => setExisting(e.target.value)} />
        </label>
        <button className="btn">Continue</button>
      </form>
      )}
    </main>
    </div>
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
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [mandates, setMandates] = useState<Mandate[]>([]);
  const [credit, setCredit] = useState<CreditCards | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [m, p, sc, dd, cc] = await Promise.all([api.me(customerId), api.payments(customerId),
        api.schedules(customerId).catch(() => [] as Schedule[]), api.mandates(customerId).catch(() => [] as Mandate[]),
        api.creditCards(customerId).catch(() => null)]);
      setCredit(cc);
      setMe(m);
      setPayments(p);
      setSchedules(sc);
      setMandates(dd);
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
    let source: EventSource | null = null;
    let closed = false;
    const wire = (es: EventSource) => {
      es.addEventListener('ready', () => setLive(true));
      es.addEventListener('bank', (ev) => {
        try {
          const e = JSON.parse((ev as MessageEvent).data) as { type: string; data: Record<string, unknown> };
          const text = describe(e.type, e.data);
          if (text) notify(text);
        } catch {
          /* ignore malformed events */
        }
        refresh();
      });
      es.onerror = () => setLive(false);
    };
    (async () => {
      const token = await auth.token();
      if (closed) return;
      source = new EventSource(token
        ? `/v1/events?access_token=${encodeURIComponent(token)}`
        : `/v1/events?customerId=${encodeURIComponent(customerId)}`);
      wire(source);
    })();
    return () => { closed = true; clearInterval(t); source?.close(); };
  }, [refresh, customerId, notify]);

  const frozen = me && me.status !== 'ACTIVE';
  return (
    <>
      <DemoBar me={me} mandates={mandates} credit={credit} notify={notify} after={refresh} />
      <header className="hero">
        <div className="hero-inner">
          <div className="hero-top">
            <span className="wordmark">Sutherland Banking Platform</span>
            <span className="spacer" />
            <span className="hero-who">{me?.account?.name ?? 'Loading…'}</span>
            <span className={`live ${live ? 'on' : ''}`} title={live ? 'Updates arrive as they happen' : 'Reconnecting'}>{live ? 'Live' : 'Offline'}</span>
            <a className="hero-link" href="/staff">Staff portal</a>
            <button className="hero-link" onClick={onSignOut}>Sign out</button>
          </div>
          <div className="hero-balance">
            <Amount value={me?.account?.available} />
            <p className="hero-sub">
              {me?.customerType === 'BUSINESS' ? 'Business current account' : 'Current account'}
              {me?.account?.sortCode ? <> · sort code <strong className="num">{fmtSort(me.account.sortCode)}</strong> · account <strong className="num">{me.account.accountNumber}</strong></> : null}
              {me?.account?.ledger && me.account.ledger !== me.account.available ? ` · ${gbp(me.account.ledger)} including pending card payments` : ''}
            </p>
          </div>
          <nav className="tabs" aria-label="Sections">
            {(['home', 'payments', 'card', 'credit'] as Tab[]).map((t) => (
              <button key={t} aria-current={tab === t ? 'page' : undefined} onClick={() => { setTab(t); setSelected(null); }}>
                {t === 'home' ? 'Overview' : t === 'payments' ? 'Payments' : t === 'card' ? 'Debit card' : 'Credit card'}
              </button>
            ))}
          </nav>
        </div>
      </header>
      <main className="shell">
        {error && <p className="alert bad">{error}</p>}
        {frozen && <p className="alert bad"><strong>Account under review.</strong> Card payments and transfers are paused while the bank completes a compliance check.</p>}
        {tab === 'home' && me && <Home me={me} payments={payments} open={(id) => { setTab('payments'); setSelected(id); }} />}
        {tab === 'payments' && me && (
          selected
            ? <PaymentDetail id={selected} back={() => setSelected(null)} customerId={customerId} notify={notify} />
            : <Payments me={me} customerId={customerId} payments={payments} schedules={schedules} mandates={mandates} open={setSelected} notify={notify} after={refresh} />
        )}
        {tab === 'card' && me && <CardView me={me} customerId={customerId} notify={notify} after={refresh} />}
        {tab === 'credit' && me && <CreditCardView me={me} customerId={customerId} credit={credit} notify={notify} after={refresh} />}
      </main>
    </>
  );
}

/** The balance in the account band: pounds large, pence small. */
function Amount({ value }: { value: string | null | undefined }) {
  if (value == null) return <div className="hero-amount">—</div>;
  const n = Number(value);
  const [pounds, pence] = Math.abs(n).toFixed(2).split('.');
  return (
    <div className="hero-amount" aria-label={gbp(value)}>
      {n < 0 ? '−' : ''}£{Number(pounds).toLocaleString('en-GB')}<span className="pence">.{pence}</span>
    </div>
  );
}

function Home({ me, payments, open }: { me: Me; payments: Payment[]; open: (id: string) => void }) {
  return (
    <>
      <section className="panel stack">
        <h2>Your account</h2>
        {me.account?.balanceError && <p className="alert warn">{me.account.balanceError}</p>}
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

const BANK_SORT = '200001';
const todayUk = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
const plusDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
const fmtDay = (iso?: string | null) => (iso ? new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '—');

type When = 'NOW' | 'LATER' | 'REPEAT';

function Payments({ me, customerId, payments, schedules, mandates, open, notify, after }: {
  me: Me; customerId: string; payments: Payment[]; schedules: Schedule[]; mandates: Mandate[];
  open: (id: string) => void; notify: (m: string) => void; after: () => void;
}) {
  const [form, setForm] = useState({
    name: 'Arjun Mehta', sortCode: '040004', accountNumber: '12345678', amount: '15.00', reference: 'Dinner',
    scheme: 'FPS' as 'FPS' | 'CHAPS', when: 'NOW' as When, date: plusDays(todayUk(), 1), frequency: 'MONTHLY', endDate: '',
  });
  const [cop, setCop] = useState<{ key: string; result: Cop; accepted: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  const sort = form.sortCode.replace(/\D/g, '');
  const sameBank = sort === BANK_SORT;
  const key = `${form.name.trim()}|${sort}|${form.accountNumber}`;
  const checked = cop && cop.key === key ? cop : null;
  const canSend = !!checked && (checked.result.result === 'MATCH' || checked.result.result === 'UNAVAILABLE'
    || (checked.result.result === 'CLOSE_MATCH' && checked.accepted));
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch });

  const check = async () => {
    setBusy(true);
    try {
      const r = await api.confirmPayee(customerId, { name: form.name.trim(), sortCode: sort, accountNumber: form.accountNumber });
      setCop({ key, result: r, accepted: false });
    } catch (err) {
      setCop({ key, result: { result: 'UNAVAILABLE' }, accepted: false });
      notify(err instanceof ApiError ? `Name check unavailable: ${err.message}` : 'Name check unavailable');
    } finally {
      setBusy(false);
    }
  };

  const useSuggested = () => {
    const name = checked?.result.matchedName;
    if (!name) return;
    setForm({ ...form, name });
    setCop({ key: `${name}|${sort}|${form.accountNumber}`, result: { result: 'MATCH' }, accepted: false });
  };

  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!me.account) return;
    if (!checked) { await check(); return; }
    if (!canSend) return;
    setBusy(true);
    const creditor = { name: form.name.trim(), sortCode: sort, accountNumber: form.accountNumber };
    const amount = { value: Number(form.amount).toFixed(2), currency: 'GBP' };
    const copResult = checked.result.result === 'CLOSE_MATCH' ? 'CLOSE_MATCH' : checked.result.result;
    const scheme = sameBank ? undefined : form.scheme;
    try {
      if (form.when === 'NOW') {
        const r = await api.pay(customerId, {
          debtorAccount: me.account.accountId, creditor, amount, remittanceInfo: form.reference, copResult, scheme,
        });
        notify(`Payment accepted · ${r.correlationId}`);
        after();
        open(r.paymentId);
      } else {
        await api.createSchedule(customerId, {
          kind: form.when === 'LATER' ? 'ONE_OFF' : 'STANDING_ORDER',
          frequency: form.when === 'REPEAT' ? form.frequency : undefined,
          startDate: form.date, endDate: form.when === 'REPEAT' && form.endDate ? form.endDate : undefined,
          debtorAccount: me.account.accountId, creditor, amount, reference: form.reference, scheme, copResult,
        });
        notify(form.when === 'LATER' ? `Payment scheduled for ${fmtDay(form.date)}` : 'Standing order set up');
        setTimeout(after, 1200);
      }
    } catch (err) {
      notify(err instanceof ApiError ? `${err.code ?? 'Error'}: ${err.message}` : 'Payment failed');
    } finally {
      setBusy(false);
    }
  };

  const r = checked?.result;
  return (
    <>
      <form className="panel stack" onSubmit={send}>
        <h2>Send money</h2>
        <p className="muted">Sort code 20-00-01 is this bank (instant transfer). Others go by Faster Payments, or CHAPS for large same-day payments. We check the name with the payee's bank first (Confirmation of Payee). 99-xx-xx is rejected by the scheme; 98-xx-xx is a bank outside the name check.</p>
        <div className="form">
          <label className="field">Payee name<input id="payee" required value={form.name} onChange={(e) => set({ name: e.target.value })} /></label>
          <label className="field">Sort code<input id="sort" required pattern="\d{2}-?\d{2}-?\d{2}" value={form.sortCode} onChange={(e) => set({ sortCode: e.target.value })} /></label>
          <label className="field">Account number<input id="acc" required pattern="\d{8}" value={form.accountNumber} onChange={(e) => set({ accountNumber: e.target.value })} /></label>
          <label className="field">Amount (£)<input id="amount" required inputMode="decimal" pattern="\d+(\.\d{1,2})?" value={form.amount} onChange={(e) => set({ amount: e.target.value })} /></label>
          <label className="field">Reference<input id="ref" maxLength={18} value={form.reference} onChange={(e) => set({ reference: e.target.value })} /></label>
        </div>
        <div className="row wrap">
          <span className="seg" role="group" aria-label="When">
            {(['NOW', 'LATER', 'REPEAT'] as When[]).map((w) => (
              <button type="button" key={w} aria-pressed={form.when === w} onClick={() => set({ when: w })}>
                {w === 'NOW' ? 'Pay now' : w === 'LATER' ? 'On a date' : 'Standing order'}
              </button>
            ))}
          </span>
          {!sameBank && (
            <span className="seg" role="group" aria-label="Scheme">
              <button type="button" aria-pressed={form.scheme === 'FPS'} onClick={() => set({ scheme: 'FPS' })}>Faster Payment · free</button>
              <button type="button" aria-pressed={form.scheme === 'CHAPS'} onClick={() => set({ scheme: 'CHAPS' })}>CHAPS · £20, same day</button>
            </span>
          )}
        </div>
        {form.when !== 'NOW' && (
          <div className="form">
            <label className="field">{form.when === 'LATER' ? 'Pay on' : 'First payment'}<input id="date" type="date" required min={todayUk()} value={form.date} onChange={(e) => set({ date: e.target.value })} /></label>
            {form.when === 'REPEAT' && (
              <>
                <label className="field">How often
                  <select id="freq" value={form.frequency} onChange={(e) => set({ frequency: e.target.value })}>
                    <option value="MONTHLY">Monthly</option>
                    <option value="WEEKLY">Weekly</option>
                  </select>
                </label>
                <label className="field">Last payment (optional)<input id="end" type="date" min={form.date} value={form.endDate} onChange={(e) => set({ endDate: e.target.value })} /></label>
              </>
            )}
          </div>
        )}
        {!sameBank && form.scheme === 'CHAPS' && <p className="muted small">CHAPS runs 06:00–17:30 UK time on working days; outside those hours the payment waits for the next opening. Payments of £10,000 or more are checked by our team first.</p>}
        {r && (
          <div className={`alert ${r.result === 'MATCH' ? 'ok' : r.result === 'NO_MATCH' ? 'bad' : 'warn'}`} role="status">
            {r.result === 'MATCH' && <><strong>Name matches.</strong> The account is held by {form.name.trim()}.</>}
            {r.result === 'CLOSE_MATCH' && (
              <div className="stack tight">
                <span><strong>Close match.</strong> The account is held by <strong>{r.matchedName}</strong>, not {form.name.trim()}.</span>
                <div className="row">
                  <button type="button" className="btn small primary" onClick={useSuggested}>Pay {r.matchedName}</button>
                  {!checked?.accepted && <button type="button" className="btn small" onClick={() => setCop({ ...checked!, accepted: true })}>Pay {form.name.trim()} anyway</button>}
                  {checked?.accepted && <span className="muted small">You chose to keep the name you typed.</span>}
                </div>
              </div>
            )}
            {r.result === 'NO_MATCH' && <><strong>Name does not match</strong> the account. Check the details with the person you are paying; we will not send this payment.</>}
            {r.result === 'UNAVAILABLE' && <><strong>We could not check the name</strong> with that bank. Only continue if you are sure the details are right.</>}
          </div>
        )}
        <div className="row">
          <button className="btn primary" disabled={busy || me.status !== 'ACTIVE' || (!!checked && !canSend)}>
            {busy ? 'Working…' : !checked ? 'Check name' : form.when === 'NOW' ? 'Send' : form.when === 'LATER' ? 'Schedule payment' : 'Set up standing order'}
          </button>
          {checked && <button type="button" className="btn small" onClick={() => setCop(null)}>Check again</button>}
        </div>
      </form>
      <Schedules customerId={customerId} schedules={schedules} notify={notify} after={after} />
      <DirectDebits customerId={customerId} mandates={mandates} notify={notify} after={after} />
      <section className="panel stack">
        <h2>All payments</h2>
        <PaymentList payments={payments} open={open} />
      </section>
    </>
  );
}

function Schedules({ customerId, schedules, notify, after }: {
  customerId: string; schedules: Schedule[]; notify: (m: string) => void; after: () => void;
}) {
  const act = async (fn: () => Promise<unknown>, text: string) => {
    try {
      await fn();
      notify(text);
      setTimeout(after, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Request failed');
    }
  };
  return (
    <section className="panel stack">
      <h2>Standing orders and scheduled payments</h2>
      {!schedules.length ? <p className="muted">None yet. Choose “On a date” or “Standing order” above.</p> : (
        <div className="scroll">
          <table className="tx">
            <tbody>
              {schedules.map((s) => {
                const c = parseCreditor(s.creditor);
                const active = s.status === 'ACTIVE';
                return (
                  <tr key={s.scheduleId}>
                    <td>
                      <div>To {c.name} · <span className="num">{gbp(s.amount)}</span></div>
                      <div className="meta">
                        {s.kind === 'ONE_OFF' ? `Once on ${fmtDay(s.startDate)}` : `${s.frequency === 'WEEKLY' ? 'Weekly' : 'Monthly'} from ${fmtDay(s.startDate)}${s.endDate ? ` to ${fmtDay(s.endDate)}` : ''}`}
                        {active && s.nextDate ? ` · next ${fmtDay(s.nextDate)}` : ''}
                        {s.paymentsMade ? ` · ${s.paymentsMade} paid${s.lastStatus && s.lastStatus !== 'COMPLETED' ? ` (last ${s.lastStatus.toLowerCase()})` : ''}` : ''}
                        {s.scheme === 'CHAPS' ? ' · CHAPS' : ''}
                      </div>
                    </td>
                    <td><Pill status={s.status} /></td>
                    <td className="actions">
                      {active && <button className="btn small linklike" title="Demo: make the next payment now" onClick={() => act(() => api.runScheduleNow(customerId, s.scheduleId), 'Next payment sent now (demo)')}>Pay next now</button>}
                      {active && <button className="btn small danger" onClick={() => act(() => api.cancelSchedule(customerId, s.scheduleId), 'Cancelled')}>Cancel</button>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function DirectDebits({ customerId, mandates, notify, after }: {
  customerId: string; mandates: Mandate[]; notify: (m: string) => void; after: () => void;
}) {
  const cancel = async (m: Mandate) => {
    try {
      await api.cancelMandate(customerId, m.mandateId);
      notify(`Cancelling your Direct Debit to ${m.originatorName}`);
      setTimeout(after, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Request failed');
    }
  };
  return (
    <section className="panel stack">
      <h2>Direct Debits</h2>
      {!mandates.length ? <p className="muted">None yet. Companies set up Direct Debits with your account details; use “New Direct Debit” in the demo bar.</p> : (
        <div className="scroll">
          <table className="tx">
            <tbody>
              {mandates.map((m) => (
                <tr key={m.mandateId}>
                  <td>
                    <div>{m.originatorName}</div>
                    <div className="meta">Service user {m.serviceUserNumber} · reference {m.payerReference ?? '—'} · since {fmtDay(m.createdAt.slice(0, 10))}
                      {m.lastAmount ? ` · last ${gbp(m.lastAmount)} ${m.lastStatus?.toLowerCase()}` : ''}</div>
                  </td>
                  <td><Pill status={m.status} /></td>
                  <td className="actions">{m.status === 'ACTIVE' && <button className="btn small danger" onClick={() => cancel(m)}>Cancel</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">Protected by the Direct Debit Guarantee: if a Direct Debit is taken in error, open it under All payments and claim a full, immediate refund.</p>
    </section>
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
                  <div className="meta">{label(p.paymentType, c.scheme)}{p.scheduleId ? ' · standing order' : ''} · {toDate(p.createdAt).toLocaleString('en-GB')}</div>
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

function PaymentDetail({ id, back, customerId, notify }: { id: string; back: () => void; customerId: string; notify: (m: string) => void }) {
  const [p, setP] = useState<Payment | null>(null);
  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const r = await api.payment(id);
        if (!stop) setP(r);
        if (['COMPLETED', 'REJECTED', 'FAILED', 'FLAGGED', 'RETURNED', 'REFUNDED'].includes(r.status)) return;
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
              <p className="eyebrow">{label(p.paymentType, c.scheme)}</p>
              <h2>{p.paymentType === 'INBOUND' ? `From ${c.debtorName}` : `To ${c.name}`}</h2>
            </div>
            <div className="row"><span className="balance small num">{gbp(p.amount)}</span><Pill status={p.status} /></div>
          </div>
          <dl className="kv">
            <div><dt>Correlation ID</dt><dd className="mono small">{p.correlationId}</dd></div>
            <div><dt>Payment ID</dt><dd className="mono small">{p.paymentId}</dd></div>
            {p.finastraRef && <div><dt>{p.paymentType === 'DIRECT_DEBIT' ? 'Bacs reference' : 'Finastra reference'}</dt><dd className="mono small">{p.finastraRef}</dd></div>}
            {p.copResult && <div><dt>Name check</dt><dd>{p.copResult.replace('_', ' ').toLowerCase()}</dd></div>}
            {p.fee && Number(p.fee) > 0 && <div><dt>Fee</dt><dd className="num">{gbp(p.fee)}</dd></div>}
            {p.paymentType === 'DIRECT_DEBIT' && <div><dt>Service user</dt><dd>{c.serviceUserNumber} · {c.payerReference}</dd></div>}
          </dl>
          {p.paymentType === 'DIRECT_DEBIT' && p.status === 'COMPLETED' && (
            <div className="row">
              <button className="btn small" onClick={async () => {
                try {
                  await api.claimRefund(customerId, p.paymentId);
                  notify('Refund requested under the Direct Debit Guarantee');
                  setTimeout(() => api.payment(id).then(setP).catch(() => undefined), 2500);
                } catch (err) {
                  notify(err instanceof ApiError ? err.message : 'Claim failed');
                }
              }}>Claim a refund (Direct Debit Guarantee)</button>
            </div>
          )}
          <h3>Timeline</h3>
          <ol className="timeline">
            {p.history?.map((h, i) => (
              <li key={i} className={['REJECTED', 'FAILED', 'FLAGGED'].includes(h.to) ? 'bad' : ''}>
                <span className="meta">{toDate(h.at).toLocaleTimeString('en-GB')} · {h.actor}</span>
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

function DemoBar({ me, mandates, credit, notify, after }: { me: Me | null; mandates: Mandate[]; credit: CreditCards | null; notify: (m: string) => void; after: () => void }) {
  const cc = credit?.accounts.find((a) => a.status === 'ACTIVE' || a.status === 'ARREARS');
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
        <button className="bbtn" disabled={!cc} onClick={() => run(async () => {
          const d = await demo.tap(cc!.cardToken, amount, name, channel);
          return d.decision === 'APPROVE' ? `Credit card approved ${gbp(amount)} at ${name} (${d.latencyMs} ms)` : `Credit card declined: ${d.reason}`;
        })}>Tap credit card</button>
        <button className="bbtn" disabled={!cc} onClick={() => run(async () => {
          const s = await demo.creditStatement(cc!.accountId, 60);
          return `Thredd statement: ${gbp(s.balance)}, minimum ${gbp(s.minimumPayment)} due in 60 seconds (demo)`;
        })}>Credit statement</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.receive(me!.account!.accountId, '20.00');
          return 'Arjun sent you £20 by Faster Payment';
        })}>Receive £20</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.salary(me!.account!.accountId, '2500.00');
          return 'ACME LTD paid your salary of £2,500 by Bacs';
        })}>Salary (Bacs)</button>
        <button className="bbtn" disabled={!me?.account} onClick={() => run(async () => {
          await demo.newDirectDebit(me!.account!.accountId, 'Brightside Energy', '275431');
          return 'Brightside Energy set up a Direct Debit on your account';
        })}>New Direct Debit</button>
        <button className="bbtn" disabled={!mandates.some((m) => m.status === 'ACTIVE') && !mandates.length} onClick={() => run(async () => {
          const m = mandates.find((x) => x.status === 'ACTIVE') ?? mandates[0];
          await demo.collect(m.mandateRef, '45.00');
          return `${m.originatorName} collected £45 by Direct Debit`;
        })}>Collect Direct Debit</button>
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
    : ['REJECTED', 'FAILED', 'FROZEN', 'BLOCKED', 'FLAGGED', 'RETURNED'].includes(status) ? 'bad' : 'n';
  return <span className={`pill p-${tone}`}>{status.replace('_', ' ').toLowerCase()}</span>;
}

function parseCreditor(json?: string | null): Record<string, string | undefined> {
  try {
    return json ? JSON.parse(json) : {};
  } catch {
    return {};
  }
}

const label = (type: string, scheme?: string) =>
  type === 'INBOUND' && scheme === 'BACS' ? 'Bacs credit'
    : ({ INTERNAL: 'Same-bank transfer', FPS: 'Faster Payment', CHAPS: 'CHAPS', BACS: 'Bacs', INBOUND: 'Money in', DIRECT_DEBIT: 'Direct Debit' } as Record<string, string>)[type] ?? type;

/** Short customer-facing text for a pushed event, or null to refresh silently. */
function describe(type: string, d: Record<string, unknown>): string | null {
  const amount = (d.amount as { value?: string } | undefined)?.value;
  switch (type) {
    case 'card.authorised': return `Card payment ${gbp(amount)} at ${d.merchant}`;
    case 'card.declined': return `Card declined at ${d.merchant}: ${d.reason}`;
    case 'payment.completed': return d.type === 'DIRECT_DEBIT' ? `Direct Debit of ${gbp(amount)} paid to ${d.originatorName}` : `Payment of ${gbp(amount)} completed`;
    case 'payment.held': return `Payment of ${gbp(amount)} is waiting for approval by our team`;
    case 'payment.queued': return `CHAPS payment of ${gbp(amount)} will go when CHAPS opens`;
    case 'payment.presented': return `${d.originatorName} will collect ${gbp(amount)} by Direct Debit on ${fmtDay(String(d.dueDate))}`;
    case 'payment.returned': return `Direct Debit of ${gbp(amount)} to ${d.originatorName} was not paid: ${d.reason}`;
    case 'payment.refunded': return `Refunded ${gbp(amount)} under the Direct Debit Guarantee`;
    case 'directdebit.mandate_created': return `New Direct Debit set up for ${d.originatorName}`;
    case 'directdebit.mandate_cancelled': return `Direct Debit to ${d.originatorName} cancelled`;
    case 'directdebit.claim_refused': return `Refund not possible: ${d.reason}`;
    case 'schedule.created': return d.kind === 'ONE_OFF' ? `Payment to ${d.creditorName} scheduled` : `Standing order to ${d.creditorName} set up`;
    case 'schedule.cancelled': return `${d.kind === 'ONE_OFF' ? 'Scheduled payment' : 'Standing order'} to ${d.creditorName} cancelled`;
    case 'payment.rejected': return `Payment of ${gbp(amount)} rejected: ${d.reason}`;
    case 'creditcard.offered': return `Credit card offer: ${gbp(String(d.creditLimit))} at ${d.purchaseApr}% APR`;
    case 'creditcard.declined': return 'Your credit card application was declined';
    case 'creditcard.referred': return 'Your credit card application is with an underwriter';
    case 'creditcard.opened': return `Your credit card ending ${d.last4} is ready`;
    case 'creditcard.repayment_completed': return 'Credit card repayment received';
    case 'creditcard.statement_ready': return `New credit card statement: minimum ${gbp(String(d.minimumPayment))} due ${d.dueDate}`;
    case 'creditcard.payment_missed': return `Credit card minimum payment missed: £${d.lateFee} fee charged`;
    case 'payment.received': return d.status === 'FLAGGED' ? `${gbp(amount)} received and held for review` : `You received ${gbp(amount)} from ${d.debtorName}${d.scheme === 'BACS' ? ' by Bacs' : ''}`;
    case 'payment.released': return `Held payment of ${gbp(amount)} released`;
    case 'customer.status_changed': return d.to === 'FROZEN' ? 'Your account is under review' : d.to === 'ACTIVE' ? 'Your account is fully available' : null;
    case 'card.controls_changed': return 'Card settings updated';
    default: return null;
  }
}

const fmtSort = (s?: string | null) => (s ? `${s.slice(0, 2)}-${s.slice(2, 4)}-${s.slice(4, 6)}` : '—');
