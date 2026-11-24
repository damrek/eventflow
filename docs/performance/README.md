# Performance

Two k6 scenarios, the command that runs them, and the numbers one machine actually produced. The
raw result object of every recorded run is committed under `results/`, so a published number can
always be traced back to a command and an environment.

**Recorded on this machine:** `read-path` finished with 0 % failed requests, avg 2.0 ms and p95
2.3 ms at 20 iterations/s; `reservation-contention` created exactly 50 holds and refused 150 of 200
attempts with no other answer, leaving `available + held + sold = capacity`. The sections below say
how to reproduce them, and what they do not prove.

Load testing is not part of CI: it needs a running stack and a quiet machine, and a PR pipeline that
measures performance would produce numbers nobody can compare (`docs/06-roadmap-calidad.md`).

## Scenarios

| Scenario | Question it answers |
| --- | --- |
| `read-path` | How fast does the system serve what the SPA polls — the published-event list and one event's live availability — under a browsing burst? |
| `reservation-contention` | Under a burst of holds on a small stock, does the conditional `UPDATE` (ADR 0006) keep `available + held + sold = capacity` with zero oversell, and what does a refusal cost in latency? |

Both seed their own venue and event through the front door (`POST /api/catalog/venues`, `POST
/api/catalog/events`, `POST .../publish`), wait for Booking to provision the inventory from
`EventPublished` (ADR 0009) and then send load at `http://localhost:8080`. Each iteration uses its own
user and `Idempotency-Key`, because both the rate limiter and idempotency are per user.

`reservation-contention` treats `422 INSUFFICIENT_TICKETS` as a **correct** answer and everything else
as a defect: `holds_created` must equal the capacity, `holds_refused` must equal the attempts minus
the capacity and `holds_other` must be `0`, all three as thresholds. Its `teardown` then reads the
availability and throws when `available + held + sold != capacity`, when a counter went negative, or
when the units actually held are not exactly the capacity — a hold that vanished would keep the sum
balanced while quietly losing a ticket. The closing availability is also written as the
`final_available` / `final_held` / `final_sold` gauges, so the numbers a document quotes are inside
the committed result object instead of only in a console line.

## Running them

```bash
./scripts/stack-up.sh                    # the Compose stack, warm
./scripts/load-test.sh read-path
./scripts/load-test.sh reservation-contention
VUS=40 CAPACITY=50 ITERATIONS=5 ./scripts/load-test.sh reservation-contention   # the same defaults
```

The runner uses the pinned `grafana/k6:2.4.0` image with `--network host` (so the base URL is the
same inside and outside the container) and writes two files per run: `results/<timestamp>-<scenario>.json`
(the complete result object) and `results/<timestamp>-<scenario>.txt` (the summary k6 printed).
`BASE_URL` points it elsewhere. A threshold failure — or a thrown teardown assertion — exits
non-zero: the command is the gate.

## Recorded run — 2026-11-24

**Hardware:** Intel Core i5-10400F (6 cores, 12 threads), 30 GiB RAM, Linux 7.0.0, Docker 29.1.3,
Docker Compose v5.7.0. **Stack:** `core` + `app` profiles on one host — PostgreSQL, RabbitMQ, Redis
and the six `eventflow/*:local` images, no k3s.

The stack was already serving traffic (`scripts/demo.sh` and the observability gate had run) before
the load, so these numbers describe a warm JVM, not a cold start.

### `read-path` — ramps to 20 iterations/s, holds 30 s, ~60 s total (80 % list / 20 % availability)

| Metric | Value | Threshold |
| --- | --- | --- |
| Iterations / requests | 949 / 954 in 60.5 s | — |
| Failed requests | 0.00 % | `< 1 %` |
| Latency avg / p95 / max | 2.0 ms / 2.3 ms / 5.7 ms | `p(95) < 150 ms`, `p(99) < 400 ms` |

Raw: `results/2026-11-24T14-08-48-read-path.json` and its `.txt` summary.

### `reservation-contention` — 40 VUs × 5 iterations against 50 units

| Metric | Value | Threshold |
| --- | --- | --- |
| Attempts | 200 | — |
| Holds created (`201`) | 50 | `== 50` |
| Refused for stock (`422`) | 150 | `== 150` |
| Any other answer | 0 | `== 0` |
| Failed requests | 0.00 % | `< 1 %` |
| Latency avg / p95 / max | 50.4 ms / 147.3 ms / 183.9 ms | `p(95) < 300 ms` |
| Availability after the run (`final_*`) | available 0, held 50, sold 0 of capacity 50 | `available + held + sold = capacity`, `held == capacity` |

Raw: `results/2026-11-24T14-06-53-reservation-contention.json` and its `.txt` summary.

The write path is an order of magnitude slower than the read path because every attempt is a real
`UPDATE` against PostgreSQL inside a transaction that also writes the outbox row and claims the
idempotency key. That is the cost of the guarantee, and the refusals are cheap only in the sense that
they never take a unit.

## What these numbers do not prove

- They are **one machine, one broker, one database**. The k3d deployment (two Booking replicas) was
  not measured; nothing here says how the system scales out.
- There is no soak test and no memory-pressure run: a 60 s burst is not a 24 h production profile.
- The scenarios exercise the read path and the hold path. Checkout, payment and the notification
  consumers were measured by the end-to-end gate (`scripts/observability-verify.sh`), not by load.
- Latency here includes the nginx front door and the JVM, so it is not a database benchmark.
