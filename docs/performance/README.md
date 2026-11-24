# Performance

Two k6 scenarios, the command that runs them, and the numbers one machine actually produced. The
raw result object of every recorded run is committed under `results/`, so a published number can
always be traced back to a command and an environment.

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

`reservation-contention` treats `422 INSUFFICIENT_TICKETS` as a **correct** answer: `holds_created`
must equal the capacity and `holds_refused` must equal the attempts minus the capacity. Both are
thresholds, so an oversell — or a lost unit — fails the run instead of hiding in a log line. Its
`teardown` reads availability afterwards and throws when `available + held + sold != capacity` or a
counter went negative.

## Running them

```bash
./scripts/stack-up.sh                    # the Compose stack, warm
./scripts/load-test.sh read-path
./scripts/load-test.sh reservation-contention
VUS=40 CAPACITY=50 ITERATIONS=5 ./scripts/load-test.sh reservation-contention   # the same defaults
```

The runner uses the pinned `grafana/k6:2.4.0` image with `--network host` (so the base URL is the
same inside and outside the container) and writes `results/<timestamp>-<scenario>.json`. `BASE_URL`
points it elsewhere. A threshold failure exits non-zero: the command is the gate.

## Recorded run — 2026-11-24

**Hardware:** Intel Core i5-10400F (6 cores, 12 threads), 30 GiB RAM, Linux 7.0.0, Docker 29.1.3,
Docker Compose v5.7.0. **Stack:** `core` + `app` profiles on one host — PostgreSQL, RabbitMQ, Redis
and the six `eventflow/*:local` images, no observability profile, no k3s.

The stack was already serving traffic (`scripts/demo.sh` had run the full flow) before the load, so
these numbers describe a warm JVM, not a cold start.

### `read-path` — 20 iterations/s for 30 s, 80 % list / 20 % availability

| Metric | Value | Threshold |
| --- | --- | --- |
| Iterations / requests | 950 / 955 in 60.5 s | — |
| Failed requests | 0.00 % | `< 1 %` |
| Latency avg / p95 / max | 2.6 ms / 3.1 ms / 14.0 ms | `p(95) < 150 ms`, `p(99) < 400 ms` |

Raw: `results/2026-11-24T12-15-11-read-path.json`.

### `reservation-contention` — 40 VUs × 5 iterations against 50 units

| Metric | Value | Threshold |
| --- | --- | --- |
| Attempts | 200 | — |
| Holds created (`201`) | 50 | `== 50` |
| Refused for stock (`422`) | 150 | `== 150` |
| Other answers | 0 | — |
| Failed requests | 0.00 % | `< 1 %` |
| Latency avg / p95 / max | 62.6 ms / 159.3 ms / 221.3 ms | `p(95) < 300 ms` |
| Availability after the run | capacity 50, available 0, held 50, sold 0 | `available + held + sold = capacity` |

Raw: `results/2026-11-24T12-17-37-reservation-contention.json`.

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
