import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const api = async (path, token, options = {}) => {
  const response = await fetch(`/api${path}`, { ...options, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(options.headers || {}) } });
  if (!response.ok) {
    const err = new Error((await response.text()) || `Request failed (${response.status})`);
    err.status = response.status;
    // No per-call code checks for this - an expired/invalid JWT can surface
    // from any endpoint, and without this every caller would need its own
    // 401 handling or the user is just stuck staring at a raw error string
    // with no way back to the login screen.
    if (response.status === 401 && token) {
      window.dispatchEvent(new CustomEvent('session-expired'));
    }
    throw err;
  }
  return response.status === 204 ? null : response.json();
};
const money = value => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(value || 0);

function Login({ onLogin }) {
  const [mode, setMode] = useState('dealer'), [signup, setSignup] = useState(false), [username, setUsername] = useState(''), [password, setPassword] = useState(''), [name, setName] = useState(''), [email, setEmail] = useState(''), [phone, setPhone] = useState(''), [error, setError] = useState('');
  // Dealer and Admin share one username/password pair in state, so without
  // this a credential typed into one tab kept showing when switching to
  // the other. Each tab switch resets to that tab's own starting point -
  // empty for Dealer, the known dev admin default for Admin - instead of
  // leaking whatever was last typed into the other tab.
  const switchMode = m => { setMode(m); if (m === 'admin') { setUsername('admin'); setPassword('admin-dev-secret-CHANGE-ME'); } else { setUsername(''); setPassword(''); } };
  const submit = async e => { e.preventDefault(); try { if (signup) { await api('/tenants', null, { method: 'POST', body: JSON.stringify({ name, username, password, email, phone }) }); setError('Signup submitted. An admin must approve your onboarding before you can sign in.'); setSignup(false); return; } const data = await api(mode === 'admin' ? '/auth/admin-login' : '/auth/login', null, { method: 'POST', body: JSON.stringify({ username, password }) }); const claims = JSON.parse(atob(data.token.split('.')[1])); onLogin({ token: data.token, role: claims.role, tenantId: claims.sub }); } catch (err) { setError(err.message); } };
  return <main className="login"><section className="card"><h1>Imperial Distribution</h1><p>{signup ? 'Agent onboarding' : 'Distribution operations portal'}</p>{!signup && <div className="tabs"><button className={mode === 'dealer' ? 'active' : ''} onClick={() => switchMode('dealer')}>Dealer</button><button className={mode === 'admin' ? 'active' : ''} onClick={() => switchMode('admin')}>Admin</button></div>}<form onSubmit={submit}>{signup && <><label>Business name<input required value={name} onChange={e => setName(e.target.value)} /></label><label>Contact email<input type="email" required value={email} onChange={e => setEmail(e.target.value)} /></label><label>Phone<input value={phone} onChange={e => setPhone(e.target.value)} /></label></>}<label>Username<input required value={username} onChange={e => setUsername(e.target.value)} /></label><label>Password<input required minLength="8" type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={signup ? 'At least 8 characters' : ''} /></label><button className="primary">{signup ? 'Submit for review' : 'Sign in'}</button>{!signup && mode === 'dealer' && <button type="button" onClick={() => { setSignup(true); setUsername(''); setPassword(''); }}>Create agent account</button>}{signup && <button type="button" onClick={() => setSignup(false)}>Back to sign in</button>}{error && <p className={error.startsWith('Signup') ? 'message' : 'error'}>{error}</p>}</form></section></main>;
}

function Dealer({ session }) {
  const [catalog, setCatalog] = useState([]), [orders, setOrders] = useState([]), [invoices, setInvoices] = useState([]), [sku, setSku] = useState(''), [qty, setQty] = useState(1), [message, setMessage] = useState(''), [submitting, setSubmitting] = useState(false), [hasCredit, setHasCredit] = useState(null);
  const refresh = async () => { try { const [c, o, i] = await Promise.all([api(`/catalog/${session.tenantId}`, session.token), api('/orders', session.token), api('/invoices', session.token)]); setCatalog(c || []); setOrders(o || []); setInvoices(i || []); if (!sku && c && c[0]) setSku(c[0].sku); } catch (e) { setMessage(e.message); } };
  const checkCredit = async () => {
    // Without this, "Place order" stays clickable for a freshly-approved
    // dealer with no credit assigned yet, and every click just comes back
    // REJECTED - confusing when the real problem is "ask your admin to
    // fund your account", not anything about this specific order.
    try { await api(`/credit/${session.tenantId}/account`, session.token); setHasCredit(true); }
    catch (err) { setHasCredit(err.status === 404 ? false : null); }
  };
  useEffect(() => { refresh(); checkCredit(); }, []);
  const placeOrder = async e => {
    e.preventDefault();
    if (submitting || hasCredit === false) return; // a double-click would otherwise fire two requests with two different Idempotency-Keys, placing two real orders
    const product = catalog.find(x => x.sku === sku);
    if (!product || !qty || Number(qty) <= 0) { setMessage('select a product and a positive quantity'); return; }
    setSubmitting(true);
    try {
      await api('/orders', session.token, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ items: [{ sku, qty: Number(qty), unitPrice: product.price }] }) });
      setMessage('Order submitted.');
    } catch (err) {
      // A REJECTED/FAILED order still comes back as a real order view, just
      // on a non-2xx status - api() only hands back the raw response text,
      // so pull the actual reason out of it instead of showing raw JSON.
      try { const body = JSON.parse(err.message); setMessage(body.rejectionReason ? `Order rejected: ${body.rejectionReason}` : err.message); }
      catch { setMessage(err.message); }
    } finally {
      setSubmitting(false);
      refresh();
      checkCredit();
    }
  };
  return <><section className="card"><h2>Place a new order</h2>{hasCredit === false && <p className="error">No credit limit assigned to your account yet - contact an admin before placing an order.</p>}<form className="row" onSubmit={placeOrder}><label>Product<select value={sku} onChange={e => setSku(e.target.value)}>{catalog.map(p => <option key={p.sku} value={p.sku}>{p.name} — {money(p.price)}</option>)}</select></label><label>Quantity<input min="1" type="number" value={qty} onChange={e => setQty(e.target.value)} /></label><button className="primary" disabled={submitting || hasCredit === false}>{submitting ? 'Placing...' : 'Place order'}</button></form>{message && <p className="message">{message}</p>}</section><section className="card"><h2>Orders</h2><Table headers={['Order', 'Status', 'Total', 'Invoice']} rows={orders.map(o => [o.id.slice(0, 8), o.status, money(o.totalValue), o.invoiceId?.slice(0, 8) || '—'])}/></section><section className="card"><h2>Invoices</h2><Table headers={['Invoice', 'Status', 'Amount', 'Due']} rows={invoices.map(i => [i.id.slice(0, 8), i.status, money(i.amount), i.dueDate])}/></section></>;
}

function Admin({ session }) {
  const [data, setData] = useState({ utilization: [], alerts: [], requests: [], onboarding: [], tenants: [], report: null }), [message, setMessage] = useState(''), [tenantId, setTenantId] = useState(''), [newLimit, setNewLimit] = useState(''), [reason, setReason] = useState(''), [submitting, setSubmitting] = useState(false), [suggestion, setSuggestion] = useState(null);
  const refresh = async () => { try { const [utilization, alerts, requests, onboarding, tenants, report] = await Promise.all([api('/admin/utilization', session.token), api('/admin/alerts', session.token), api('/admin/limit-increase-requests?status=PENDING', session.token), api('/admin/onboarding', session.token), api('/admin/onboarding?status=ACTIVE', session.token), api('/admin/reports/monthly/latest', session.token).catch(() => null)]); setData({ utilization: utilization || [], alerts: alerts || [], requests: requests || [], onboarding: onboarding || [], tenants: tenants || [], report }); } catch (e) { setMessage(e.message); } };
  useEffect(() => { refresh(); }, []);
  const decide = async (id, action) => { try { await api(`/admin/limit-increase-requests/${id}/${action}`, session.token, { method: 'POST' }); refresh(); } catch (e) { setMessage(e.message); } };
  const idToUsername = id => data.tenants.find(t => t.id === id)?.username || id.slice(0, 8);
  // Picking from a real list - built from the same active-tenants fetch
  // the utilization table uses - instead of typing a username/id by hand
  // makes "no match for what I typed" structurally impossible.
  const selectTenant = async id => {
    setTenantId(id);
    setSuggestion(undefined); // loading
    if (!id) { setSuggestion(null); return; }
    try { setSuggestion(await api(`/credit/${id}/risk-score`, session.token)); }
    catch (err) { setSuggestion(err.status === 404 ? null : undefined); }
  };
  const override = async e => {
    e.preventDefault();
    if (submitting || !tenantId) return;
    setSubmitting(true);
    try {
      // PATCH .../limit enforces the never-below-outstanding safeguard but
      // only works once an account already exists; a brand-new tenant has
      // none yet (nothing else can create the first row), so a 404 here
      // means "provision instead", not a real failure.
      try {
        await api(`/credit/${tenantId}/limit`, session.token, { method: 'PATCH', body: JSON.stringify({ newLimit: Number(newLimit), overrideReason: reason }) });
      } catch (err) {
        if (err.status !== 404) throw err;
        await api(`/credit/${tenantId}/accounts`, session.token, { method: 'POST', body: JSON.stringify({ creditLimit: Number(newLimit) }) });
      }
      setMessage('Credit limit updated.');
      setTenantId(''); setNewLimit(''); setReason(''); setSuggestion(null);
      refresh();
    } catch (err) {
      // The never-below-outstanding safeguard rejects with 409 and a raw
      // JSON body (no readable message field) - without this, the admin
      // just saw an unparsed JSON blob and the limit silently stayed
      // unchanged, with no indication why the "update" didn't take.
      if (err.status === 409) {
        const current = data.utilization.find(x => x.tenantId === tenantId);
        setMessage(current ? `Cannot set limit below what's currently outstanding (${money(current.outstanding)}).` : "Cannot set limit below what's currently outstanding.");
      } else {
        setMessage(err.message);
      }
    } finally {
      setSubmitting(false);
    }
  };
  const reviewOnboarding = async (id, action) => { try { await api(`/admin/onboarding/${id}/${action}`, session.token, { method: 'POST', body: JSON.stringify({ note: 'Reviewed in admin dashboard' }) }); setMessage(`Onboarding ${action}d.`); refresh(); } catch (e) { setMessage(e.message); } };
  const outstanding = data.utilization.reduce((sum, x) => sum + Number(x.outstanding), 0), limit = data.utilization.reduce((sum, x) => sum + Number(x.creditLimit), 0);
  return <>{message && <p className="message">{message}</p>}<section className="metrics"><Metric title="Pool utilization" value={limit ? `${Math.round(outstanding / limit * 100)}%` : '—'} /><Metric title="Near-limit alerts" value={data.alerts.length} /><Metric title="Pending requests" value={data.requests.length} /><Metric title="Pending onboarding" value={data.onboarding.length} /></section><section className="card"><h2>Onboarding review</h2><Table headers={['Business', 'Username', 'Email', 'Actions']} rows={data.onboarding.map(a => [a.name, a.username, a.email, <span><button onClick={() => reviewOnboarding(a.id, 'approve')}>Approve</button><button className="danger" onClick={() => reviewOnboarding(a.id, 'reject')}>Reject</button></span>])}/></section><section className="card"><h2>Tenant utilization</h2><Table headers={['Tenant', 'Credit limit', 'Outstanding', 'Utilization', 'Status']} rows={data.utilization.map(x => [idToUsername(x.tenantId), money(x.creditLimit), money(x.outstanding), `${x.creditLimit ? Math.round(x.outstanding / x.creditLimit * 100) : 0}%`, x.throttled ? 'Throttled' : 'Active'])}/></section><section className="two"><section className="card"><h2>Near-limit alerts</h2><Table headers={['Tenant', 'Utilization']} rows={data.alerts.map(a => [idToUsername(a.tenantId), `${a.utilization}%`])}/></section><section className="card"><h2>Pending credit requests</h2><Table headers={['Tenant', 'Amount', 'Actions']} rows={data.requests.map(r => [idToUsername(r.tenantId), money(r.requestedAmount), <span><button onClick={() => decide(r.id, 'approve')}>Approve</button><button className="danger" onClick={() => decide(r.id, 'reject')}>Reject</button></span>])}/></section></section><section className="card"><h2>Manual credit override</h2><form className="row" onSubmit={override}><label>Tenant<select required value={tenantId} onChange={e => selectTenant(e.target.value)}><option value="">Select a tenant...</option>{data.tenants.map(t => <option key={t.id} value={t.id}>{t.name} ({t.username || t.id.slice(0, 8)})</option>)}</select></label><label>New limit<input required min="0" type="number" value={newLimit} onChange={e => setNewLimit(e.target.value)} /></label><label>Reason<input required value={reason} onChange={e => setReason(e.target.value)} /></label><button className="primary" disabled={submitting}>{submitting ? 'Applying...' : 'Apply'}</button></form>{suggestion === undefined && <p className="message">Loading risk assessment...</p>}{suggestion === null && tenantId && <p className="message">No risk assessment submitted by this tenant yet.</p>}{suggestion && <p className="message">Suggested limit: {money(suggestion.suggestedLimit)} (risk score {suggestion.score}/10). <button type="button" onClick={() => setNewLimit(suggestion.suggestedLimit)}>Use suggestion</button></p>}</section></>;
}
const Metric = ({ title, value }) => <section className="metric"><span>{title}</span><strong>{value}</strong></section>;
const Table = ({ headers, rows }) => <div className="table-wrap"><table><thead><tr>{headers.map(h => <th key={h}>{h}</th>)}</tr></thead><tbody>{rows.length ? rows.map((row, i) => <tr key={i}>{row.map((cell, j) => <td key={j}>{cell}</td>)}</tr>) : <tr><td colSpan={headers.length}>No records</td></tr>}</tbody></table></div>;
function Pending({ session }) {
  const [type, setType] = useState('GSTIN'), [file, setFile] = useState(null), [message, setMessage] = useState('Your onboarding is pending admin review.');
  const [volume, setVolume] = useState(''), [vintage, setVintage] = useState(''), [collateral, setCollateral] = useState(''), [relationship, setRelationship] = useState(0), [scoreResult, setScoreResult] = useState(null), [scoreMessage, setScoreMessage] = useState('');
  const upload = async e => { e.preventDefault(); if (!file) return; try { const form = new FormData(); form.append('type', type); form.append('file', file); const r = await fetch('/api/documents', { method: 'POST', headers: { Authorization: `Bearer ${session.token}` }, body: form }); if (!r.ok) throw new Error(await r.text()); setMessage(`${type} uploaded for review.`); } catch (e) { setMessage(e.message); } };
  const submitAssessment = async e => {
    e.preventDefault();
    try {
      const result = await api(`/credit/${session.tenantId}/risk-score`, session.token, { method: 'POST', body: JSON.stringify({ declaredMonthlyVolume: Number(volume), businessVintageYears: Number(vintage), collateralOffered: Number(collateral) || 0, relationshipCategoryPoints: Number(relationship) }) });
      setScoreResult(result);
      setScoreMessage('Submitted - an admin will see this suggestion when reviewing your credit limit.');
    } catch (err) {
      setScoreMessage(err.message);
    }
  };
  return <><section className="card"><h2>Onboarding pending review</h2><p>{message}</p><form className="row" onSubmit={upload}><label>Document type<select value={type} onChange={e=>setType(e.target.value)}><option>GSTIN</option><option>PAN</option><option>Bank proof</option></select></label><label>Document<input type="file" required onChange={e=>setFile(e.target.files[0])}/></label><button className="primary">Upload document</button></form></section><section className="card"><h2>Credit assessment</h2><p>Tell us about your business so an admin can set an appropriate starting credit limit.</p><form className="row" onSubmit={submitAssessment}><label>Declared monthly volume (₹)<input required min="0" type="number" value={volume} onChange={e=>setVolume(e.target.value)}/></label><label>Business vintage (years)<input required min="0" type="number" value={vintage} onChange={e=>setVintage(e.target.value)}/></label><label>Collateral offered (₹)<input min="0" type="number" value={collateral} onChange={e=>setCollateral(e.target.value)}/></label><button className="primary">Submit for assessment</button></form>{scoreMessage && <p className="message">{scoreMessage}</p>}{scoreResult && <p className="message">Suggested limit: {money(scoreResult.suggestedLimitAfterCeiling)} (risk score {scoreResult.score}/10)</p>}</section></>;
}
function App() {
  const [session, setSession] = useState(null);
  useEffect(() => {
    // The only thing that previously stopped a user with an expired/invalid
    // JWT from being stuck on a raw error string forever - any api() call
    // anywhere in the tree can fire this, dropping back to the login screen.
    const onExpired = () => setSession(null);
    window.addEventListener('session-expired', onExpired);
    return () => window.removeEventListener('session-expired', onExpired);
  }, []);
  if (!session) return <Login onLogin={setSession}/>;
  return <main><header><h1>Imperial Distribution</h1><span>{session.role === 'ADMIN' ? 'Admin Console' : session.role === 'PENDING' ? 'Onboarding' : 'Dealer Portal'}</span><button onClick={() => setSession(null)}>Sign out</button></header><div className="content">{session.role === 'ADMIN' ? <Admin session={session}/> : session.role === 'PENDING' ? <Pending session={session}/> : <Dealer session={session}/>}</div></main>;
}
createRoot(document.getElementById('root')).render(<App/>);
