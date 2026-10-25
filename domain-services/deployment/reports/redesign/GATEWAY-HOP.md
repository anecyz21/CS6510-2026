# Gateway hop check (task T038, baseline defect F12)

**Date**: 2026-10-25 · **Stack**: redesign Phase 3 (private stores, pass-through gateway), Docker
Desktop 29.1.3 on Windows 10 (WSL2) · **Host**: Intel Core i7-13700K, 31.7 GiB RAM

## Question

The baseline normal-load runs spent about 44 ms of a 48 ms scan inside the gateway
(`reports/baseline/OBSERVATIONS.md`). That stall appeared only on forwarded POSTs with a body.
T038 requires the redesign gateway not to reproduce it.

## Result

**The stall is not caused by gateway code. Docker Desktop's port forwarding causes it when the
gateway port is published on all interfaces (`8080:8080`), for POST requests with a body sent
from a Java client on the Windows host.** Inside the Docker network, the redesign gateway adds
about 1–2 ms per scan.

| Measurement (mean per scan POST) | Gateway | Checkout direct | Gateway adds |
|---|---:|---:|---:|
| Load client, 10 stations × 60 s, gateway JDK `HttpClient` (server timers) | 47.49 ms | 3.33 ms | 44.2 ms |
| Same, gateway on Apache HttpClient 5 with TCP_NODELAY (server timers) | 47.22 ms | 3.29 ms | 43.9 ms |
| JDK `HttpClient` probe on the Windows host, keep-alive, 200 scans | 48.0 ms | 3.3 ms (`127.0.0.1:8082`) | 44.7 ms |
| Same probe to `127.0.0.1`, `localhost` and `[::1]` on port 8080 | 48.3 / 48.1 / 48.7 ms | — | — |
| `curl` keep-alive POSTs inside the gateway container | 2.4–3.6 ms | 2.0–2.7 ms | ≈0.5 ms |
| JDK `HttpClient` probe inside the gateway container, 3 × 500 scans (warm) | 4.76 / 4.34 / 4.16 ms | 2.83 / 3.29 / 3.04 ms | 1.1–1.9 ms |
| **Host probe with the gateway temporarily published as `127.0.0.1:8080:8080`** | **6.2 ms** | — | — |
| Host probe with the gateway published as `8080:8080` again (restored) | 48.5 ms | — | — |

What rules out each explanation:
- **Not the gateway's outbound client.** Replacing the JDK `HttpClient` with Apache HttpClient 5
  and TCP_NODELAY changed nothing (row 2). The gateway therefore keeps the JDK `HttpClient` that
  research R1 specifies.
- **Not Checkout or the container network.** Both `curl` and a Java client inside Docker get about
  3 ms answers from the gateway and from Checkout.
- **Not IPv4 versus IPv6 `localhost`.** All three loopback addresses stall equally.
- **The binding is the cause.** Changing only the published binding of port 8080 from all
  interfaces to `127.0.0.1` removed the stall: 48.5 ms dropped to 6.2 ms.

The Checkout scan time measured from the host matches its server time (3.3 ms) because Checkout's
operator port is published on `127.0.0.1` only.

## Consequences

- **F12 is an environment artifact, not a defect in either gateway.** The baseline compose file
  also published `8080:8080`, so its normal-load start and scan latencies include the same stall.
- **SC-009 is unaffected.** It uses stress medians, and the stall disappears under stress load in
  the baseline: the gateway gap was about 2.3 ms.
- **Measured runs must publish port 8080 the same way in both topologies.** The redesign compose
  file keeps `8080:8080`, identical to the baseline. Changing the binding for redesign runs only
  would make normal-load latency look about 44 ms better for reasons that have nothing to do with
  the architecture.
- **T038's pass condition is met when measured where the gateway runs.** Inside the Docker network
  the gateway adds 1.1–1.9 ms, below the 5 ms limit. Measured from the Windows host, the
  `8080:8080` binding adds about 44 ms regardless of gateway code.

## Reproducing

The probe is `tools/HopProbe.java`, which posts scans exactly as the load client does: a JDK
`HttpClient`, `BodyPublishers.ofString`, and one keep-alive client. Running the unchanged load
client with `--stations=10 --duration=60` and comparing `http.server.requests` for the gateway's
`/transactions/{transactionId}/items` against Checkout's `/transactions/{id}/items` reproduces
row 1.
