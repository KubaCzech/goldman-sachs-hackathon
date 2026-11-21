import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { toast } from '../toast.js';
import { CHANNELS, CHANNEL_LABELS, asArray } from '../util.js';
import { Empty, Field, Section } from '../components/ui.jsx';
import { useUnsavedChanges } from '../unsaved.jsx';

function TeamPolicy({ team, onSaved }) {
  const { userName } = useAuth();
  const [notify, setNotify] = useState(team.notify || 'all');
  const [onCallUserId, setOnCall] = useState(team.onCallUserId || '');
  const [busy, setBusy] = useState(false);
  const members = asArray(team.members);
  const dirty = notify !== (team.notify || 'all') || onCallUserId !== (team.onCallUserId || '');

  const save = async () => {
    setBusy(true);
    try {
      await api.put(`/api/teams/${encodeURIComponent(team.id)}/policy`, { notify, onCallUserId: onCallUserId || null });
      toast.success(`Policy saved for ${team.name || team.id}`);
      onSaved();
      return true;
    } catch (e) {
      toast.error(e);
      return false;
    } finally {
      setBusy(false);
    }
  };
  useUnsavedChanges(dirty, save);

  return (
    <div className="item">
      <div className="item-head">
        <strong>{team.name || team.id}</strong>
        <button type="button" className="btn small primary" onClick={save} disabled={busy || !dirty}>{busy ? 'Saving…' : dirty ? 'Save' : 'Saved'}</button>
      </div>
      <div className="row wrap">
        <div className="field">
          <span className="field-label">When a rule notifies this team</span>
          <label className="check">
            <input type="radio" name={`notify-${team.id}`} checked={notify === 'all'} onChange={() => setNotify('all')} /> Notify every member
          </label>
          <label className="check">
            <input type="radio" name={`notify-${team.id}`} checked={notify === 'oncall'} onChange={() => setNotify('oncall')} /> Notify only the on-call person
          </label>
        </div>
        <Field label="On-call person">
          <select value={onCallUserId} onChange={(e) => setOnCall(e.target.value)}>
            <option value="">Nobody</option>
            {members.map((m) => <option key={m.userId} value={m.userId}>{m.displayName || userName(m.userId)}{m.role === 'admin' ? ' (admin)' : ''}</option>)}
            {onCallUserId && !members.some((m) => m.userId === onCallUserId) && <option value={onCallUserId}>{userName(onCallUserId)}</option>}
          </select>
        </Field>
      </div>
      {notify === 'oncall' && !onCallUserId && <div className="warn-box">Nobody is on call, so nobody in this team would be notified.</div>}
      <div className="muted">
        Members: {members.length ? members.map((m) => `${m.displayName || userName(m.userId)} (${m.role || 'member'})`).join(', ') : 'none listed'}
      </div>
    </div>
  );
}

// Tolerates [{channel, down}] or {email: false, ...}.
function normaliseChannels(data) {
  const map = {};
  if (Array.isArray(data)) data.forEach((c) => { if (c && c.channel) map[c.channel] = !!c.down; });
  else if (data && typeof data === 'object') {
    Object.entries(data).forEach(([k, v]) => { map[k] = !!(v && typeof v === 'object' ? v.down : v); });
  }
  const names = [...CHANNELS, ...Object.keys(map).filter((k) => !CHANNELS.includes(k))];
  return names.map((channel) => ({ channel, down: !!map[channel] }));
}

function ChannelSwitches() {
  const [channels, setChannels] = useState(null);
  const [denied, setDenied] = useState(null);
  const [busy, setBusy] = useState(null);

  const load = useCallback(() => {
    api.get('/api/admin/channels')
      .then((c) => { setChannels(normaliseChannels(c)); setDenied(null); })
      .catch((e) => setDenied(e.status === 403 ? 'Only a platform admin can use this.' : e.message));
  }, []);
  useEffect(load, [load]);

  const toggle = async (c) => {
    setBusy(c.channel);
    try {
      await api.put(`/api/admin/channels/${encodeURIComponent(c.channel)}`, { down: !c.down });
      toast.success(`${CHANNEL_LABELS[c.channel] || c.channel} is now ${!c.down ? 'failing' : 'working'}`);
      load();
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };

  if (denied) return <Empty>{denied}</Empty>;
  if (!channels) return <Empty>Loading channels…</Empty>;
  return (
    <table className="table">
      <thead><tr><th>Channel</th><th>State</th><th /></tr></thead>
      <tbody>
        {channels.map((c) => (
          <tr key={c.channel}>
            <td><strong>{CHANNEL_LABELS[c.channel] || c.channel}</strong></td>
            <td><span className={`badge ${c.down ? 'delivery-failed' : 'delivery-sent'}`}>{c.down ? 'Failing (simulated outage)' : 'Working'}</span></td>
            <td className="right">
              <button type="button" className={`btn small ${c.down ? 'primary' : 'danger'}`} disabled={busy === c.channel} onClick={() => toggle(c)}>
                {c.down ? 'Restore channel' : 'Simulate outage'}
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default function Admin() {
  const { adminTeams, isPlatformAdmin, reload } = useAuth();

  return (
    <div>
      <div className="page-head"><h1>Admin</h1></div>

      {adminTeams.length === 0 && !isPlatformAdmin && (
        <Empty title="Nothing to manage">
          You are not an admin of any team. Team admins can set who is notified; platform admins can simulate channel outages.
          Use "Switch user" at the top to sign in as an admin.
        </Empty>
      )}

      {adminTeams.length > 0 && (
        <Section title="Team notification policy" hint="Decides who in a team is told when a rule notifies the whole team.">
          {adminTeams.map((t) => <TeamPolicy key={`${t.id}-${t.notify}-${t.onCallUserId}`} team={t} onSaved={reload} />)}
        </Section>
      )}

      {isPlatformAdmin && (
        <Section title="Channel failure injection" hint="For demos: make a channel fail to show retries and the dead-letter queue. Failed notifications are retried up to 5 times.">
          <ChannelSwitches />
        </Section>
      )}
    </div>
  );
}
