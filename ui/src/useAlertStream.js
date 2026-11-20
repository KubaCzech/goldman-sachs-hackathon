import { useEffect, useRef, useState } from 'react';
import { getToken, streamUrl } from './api.js';

// Subscribes to the SSE alert stream. `onAlert` receives each parsed Alert. Returns true while connected.
export function useAlertStream(teamId, onAlert) {
  const handler = useRef(onAlert);
  handler.current = onAlert;
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!getToken() || typeof EventSource === 'undefined') return undefined;
    const es = new EventSource(streamUrl(teamId));
    const onEvent = (e) => {
      try {
        const a = JSON.parse(e.data);
        if (a && typeof a === 'object') handler.current(a);
      } catch {
        /* ignore keep-alives / malformed frames */
      }
    };
    es.addEventListener('alert', onEvent);
    es.onmessage = onEvent; // tolerate a backend that sends unnamed events
    es.onopen = () => setConnected(true);
    es.onerror = () => setConnected(false); // EventSource reconnects by itself
    return () => {
      es.close();
      setConnected(false);
    };
  }, [teamId]);

  return connected;
}
