import { useCallback, useEffect, useState } from 'react';
import { ApiError, gbp, toDate } from './api';

type Row = Record<string, string | null>;
type Queue = { reviews: Row[]; flaggedPayments: Row[]; stuckSagas: Row[] };
type Journey = { correlationId: string; sagas: Row[]; payments: Row[]; paymentHistory: Row[] };

async function staffFetch<T>(path: string, staff: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-Staff-User': staff },
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(body?.detail ?? `Request failed (${res.status})`, body?.code);
  return body as T;
}

/** Staff portal (Control Room): review queue, flagged payments, stuck sagas, journey search. */
export default function Staff({ notify }: { notify: (m: string) => void }) {
  const [staff, setStaff] = useState('aravind');
  const [queue, setQueue] = useState<Queue | null>(null);
  const [sagas, setSagas] = useState<Row[]>([]);
  const [search, setSearch] = useState('');
  const [journey, setJourney] = useState<Journey | null>(null);
  const [notes, setNotes] = useState<Record<string, string>>({});

  const refresh = useCallback(async () => {
    try {
      const [q, s] = await Promise.all([
        staffFetch<Queue>('/v1/staff/queue', staff),
        staffFetch<Row[]>('/v1/staff/sagas', staff),
      ]);
      setQueue(q);
      setSagas(s);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Cannot reach the bank');
    }
  }, [staff, notify]);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [refresh]);

  const decide = async (sagaId: string, decision: 'CLEAR' | 'CONFIRM') => {
    try {
      await staffFetch(`/v1/staff/reviews/${sagaId}`, staff, {
        method: 'POST',
        body: JSON.stringify({ decision, note: notes[sagaId] || (decision === 'CLEAR' ? 'False positive' : 'Confirmed match') }),
      });
      notify(decision === 'CLEAR' ? 'Cleared: customer unfrozen, cards unblocked, held payments released' : 'Confirmed: customer closed');
      setTimeout(refresh, 1500);
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Review failed');
    }
  };

  const find = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setJourney(await staffFetch<Journey>(`/v1/staff/journeys/${encodeURIComponent(search.trim())}`, staff));
    } catch (err) {
      notify(err instanceof ApiError ? err.message : 'Search failed');
    }
  };

  const open = (queue?.reviews.length ?? 0) + (queue?.flaggedPayments.length ?? 0) + (queue?.stuckSagas.length ?? 0);
  return (
    <div className="staff-theme">
      <div className="staff-bar">
        <div className="staff-bar-inner">
          <span className="staff-badge">Staff only</span>
          <strong className="staff-title">Control Room</strong>
          <span className="staff-sub">Banking Platform Prototype · operations and compliance</span>
          <span className="spacer" />
          <label className="staff-user">Signed in as
            <input id="staff" value={staff} onChange={(e) => setStaff(e.target.value)} />
          </label>
          <a className="staff-link" href="/">Customer app →</a>
        </div>
      </div>
    <main className="shell wide">
      <div className="tiles">
        <div className="tile"><span className="eyebrow">Open reviews</span><span className="tile-v">{queue?.reviews.length ?? 0}</span></div>
        <div className="tile"><span className="eyebrow">Held payments</span><span className="tile-v">{queue?.flaggedPayments.length ?? 0}</span></div>
        <div className="tile"><span className="eyebrow">Stuck sagas</span><span className="tile-v">{queue?.stuckSagas.length ?? 0}</span></div>
        <div className="tile"><span className="eyebrow">Needs attention</span><span className={`tile-v ${open ? 'warn' : ''}`}>{open}</span></div>
      </div>

      <section className="panel stack">
        <div className="row between"><h2>Compliance reviews</h2><span className="pill p-n">{queue?.reviews.length ?? 0} open</span></div>
        {!queue?.reviews.length ? <p className="muted">No open reviews. Use “Sanctions hit” in the customer app to raise one.</p> : (
          <div className="scroll">
            <table className="tx">
              <tbody>
                {queue.reviews.map((r) => (
                  <tr key={r.sagaId}>
                    <td>
                      <div className="mono small">{r.customerId}</div>
                      <div className="meta">Screening hit · frozen since {fmt(r.startedAt)} · {r.correlationId}</div>
                      <input className="note" id={`note-${r.sagaId}`} placeholder="Review note" value={notes[r.sagaId!] ?? ''}
                        onChange={(e) => setNotes({ ...notes, [r.sagaId!]: e.target.value })} />
                    </td>
                    <td className="actions">
                      <button className="btn small primary" onClick={() => decide(r.sagaId!, 'CLEAR')}>Clear</button>
                      <button className="btn small danger" onClick={() => decide(r.sagaId!, 'CONFIRM')}>Confirm match</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <div className="grid2">
        <section className="panel stack">
          <h2>Payments held for review</h2>
          {!queue?.flaggedPayments.length ? <p className="muted">None.</p> : queue.flaggedPayments.map((p) => (
            <div key={p.paymentId} className="row between line">
              <div><div className="mono small">{p.paymentId}</div><div className="meta">{p.customerId}</div></div>
              <strong className="num">{gbp(p.amount)}</strong>
            </div>
          ))}
          <p className="muted small">Released automatically when the customer's review is cleared.</p>
        </section>
        <section className="panel stack">
          <h2>Stuck sagas</h2>
          {!queue?.stuckSagas.length ? <p className="muted">None waiting more than 10 minutes.</p> : queue.stuckSagas.map((s) => (
            <div key={s.sagaId} className="row between line">
              <div><strong>{s.type}</strong> · {s.step}<div className="meta">{s.customerId}</div></div>
              <span className="pill p-n">{s.status}</span>
            </div>
          ))}
        </section>
      </div>

      <form className="panel stack" onSubmit={find}>
        <h2>Find a journey</h2>
        <div className="row">
          <input className="grow" id="search" placeholder="COR-PAY-… or COR-ONB-…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <button className="btn primary">Search</button>
        </div>
        {journey && (
          <div className="stack">
            <p className="muted small">{journey.sagas.length} saga(s), {journey.payments.length} payment(s) for {journey.correlationId}</p>
            {journey.sagas.map((s) => <div key={s.sagaId} className="line"><strong>{s.type}</strong> · {s.step} · <span className="pill p-n">{s.status}</span></div>)}
            <ol className="timeline">
              {journey.paymentHistory.map((h, i) => (
                <li key={i} className={['REJECTED', 'FAILED', 'FLAGGED'].includes(h.to ?? '') ? 'bad' : ''}>
                  <span className="meta">{fmt(h.at)} · {h.actor}</span>
                  <strong>{h.to}</strong>{h.reason ? ` — ${h.reason}` : ''}
                </li>
              ))}
            </ol>
          </div>
        )}
      </form>

      <section className="panel stack">
        <h2>Recent sagas</h2>
        <div className="scroll">
          <table className="tx">
            <tbody>
              {sagas.map((s) => (
                <tr key={s.sagaId} className="click" onClick={() => { setSearch(s.correlationId ?? ''); }}>
                  <td><strong>{s.type}</strong><div className="meta">{s.correlationId}</div></td>
                  <td>{s.step}</td>
                  <td><span className={`pill ${s.status === 'COMPLETED' ? 'p-ok' : s.status === 'FAILED' ? 'p-bad' : 'p-n'}`}>{s.status}</span></td>
                  <td className="meta">{fmt(s.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </main>
    </div>
  );
}

const fmt = (t?: string | null) => (t ? toDate(t).toLocaleString('en-GB') : '—');
