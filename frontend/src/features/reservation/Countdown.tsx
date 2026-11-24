import { useEffect, useRef, useSyncExternalStore } from "react";

import { formatDuration } from "../../components/format";

interface CountdownProps {
  /** ISO timestamp of the hold deadline, from the backend. */
  until: string;
  onExpire?: () => void;
}

/** The clock is an external, mutable source: subscribe to it instead of copying it into state. */
function subscribeToClock(onChange: () => void) {
  const timer = window.setInterval(onChange, 1000);
  return () => window.clearInterval(timer);
}

function readClock() {
  return Date.now();
}

/** Live countdown to the hold deadline. It never changes a reservation status by itself. */
export function Countdown({ until, onExpire }: CountdownProps) {
  const now = useSyncExternalStore(subscribeToClock, readClock, readClock);
  const remaining = Date.parse(until) - now;
  const onExpireRef = useRef(onExpire);

  useEffect(() => {
    onExpireRef.current = onExpire;
  }, [onExpire]);

  const expiredRef = useRef(false);
  useEffect(() => {
    if (remaining > 0) {
      expiredRef.current = false;
      return;
    }
    if (expiredRef.current) return;
    expiredRef.current = true;
    onExpireRef.current?.();
  }, [remaining]);

  return (
    <span role="timer" aria-label="Time remaining" className="font-mono tabular-nums">
      {formatDuration(remaining)}
    </span>
  );
}
