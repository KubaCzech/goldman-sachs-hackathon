// Sample events used by the rule preview and the "add schema from a sample" screen.
export const SAMPLES = [
  {
    id: 'workflow-failed',
    label: 'Workflow failed (settlements)',
    event: {
      type: 'WORKFLOW_FAILED',
      workflowId: 'WF-20261023-00042',
      workflowName: 'order-settlement',
      step: 'validate-counterparty',
      severity: 'ERROR',
      environment: 'prod',
      timestamp: '2026-10-23T09:14:02.331Z',
      error: { code: 'TIMEOUT', message: 'Counterparty service did not respond in 5000 ms' },
    },
  },
  {
    id: 'alertmanager',
    label: 'High error rate (metrics webhook)',
    event: {
      alertname: 'HighErrorRate',
      labels: { service: 'payments-api', region: 'eu-west', severity: 'critical' },
      annotations: { summary: 'Error rate 12% over the last 5 minutes' },
      startsAt: '2026-10-23T09:13:00Z',
      status: 'firing',
    },
  },
  {
    id: 'trade-rejected',
    label: 'Trade rejected (trade gateway)',
    event: {
      meta: { schema: 'trade.rejected.v2', producer: 'trade-gateway', ts: 1792833245000 },
      payload: { tradeId: 'T-998877', desk: 'rates', notional: 25000000, currency: 'EUR', reason: 'LIMIT_BREACH' },
    },
  },
  // Not JSON: the same parser the stream uses reads these (see Sources → Message format).
  {
    id: 'csv-trades',
    label: 'Trades (CSV)',
    text: 'meta.schema,payload.tradeId,payload.desk,payload.notional,payload.currency,payload.reason\ntrade.rejected.v2,T-1001,rates,25000000,EUR,LIMIT_BREACH\ntrade.rejected.v2,T-1002,fx,1200000,USD,PRICE_STALE',
  },
  {
    id: 'logfmt-pricing',
    label: 'Pricing log (key=value)',
    text: 'level=warn service=pricing-engine-2 instrument=EURUSD latencyMs=1240 msg="pricing latency above 1s, cache miss"',
  },
  {
    id: 'syslog',
    label: 'Syslog line',
    text: '<11>1 2026-10-23T09:14:02Z host-7 settlement-svc 4242 ID47 - Counterparty timeout on WF-20261023-00042',
  },
];

export const sampleText = (id) => {
  const s = SAMPLES.find((x) => x.id === id) || SAMPLES[0];
  return s.text !== undefined ? s.text : JSON.stringify(s.event, null, 2);
};
