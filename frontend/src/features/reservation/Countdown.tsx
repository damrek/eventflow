import { useEffect, useRef, useState } from "react";

import { formatDuration } from "../../components/format";

interface CountdownProps {
  /** ISO timestamp of the hold deadline, from the backend. */
  until: string;
  onExpire?: () => void;
}

/** Live countdown to the hold deadline. It never changes a reservation status by itself. */
export function Countdown({ until, onExpire }: CountdownProps) {
  const [remaining, setRemaining] = useState(() => Date.parse(until) - Date.now());
  const onExpireRef = useRef(onExpire);

  useEffect(() => {
    onExpireRef.current = onExpire;
  }, [onExpire]);

  useEffect(() => {
    const deadline = Date.parse(until);
    setRemaining(deadline - Date.now());
    const timer = window.setInterval(() => setRemaining(deadline - Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [until]);

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
