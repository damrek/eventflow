import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Countdown } from "./Countdown";

describe("Countdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("renders the remaining time and ticks down", () => {
    render(<Countdown until={new Date(Date.now() + 65_000).toISOString()} />);

    expect(screen.getByRole("timer")).toHaveTextContent("01:05");

    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(screen.getByRole("timer")).toHaveTextContent("01:00");
  });

  it("reaches zero and reports expiry once", () => {
    const onExpire = vi.fn();
    render(<Countdown until={new Date(Date.now() + 3000).toISOString()} onExpire={onExpire} />);

    expect(onExpire).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(4000);
    });

    expect(screen.getByRole("timer")).toHaveTextContent("00:00");
    expect(onExpire).toHaveBeenCalledTimes(1);

    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it("shows zero for a deadline that already passed", () => {
    render(<Countdown until={new Date(Date.now() - 10_000).toISOString()} />);

    expect(screen.getByRole("timer")).toHaveTextContent("00:00");
  });
});
