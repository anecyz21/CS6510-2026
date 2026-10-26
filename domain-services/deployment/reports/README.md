# Workload evidence

| Folder or file | Contents |
| --- | --- |
| [`baseline/`](baseline/) | Pre-migration baseline from tag `baseline/service-based-v1`: 3 normal and 3 stress runs, each with an observations file and a reconciliation output. Created in tasks T012–T013. |
| [`redesign/`](redesign/) | The same workloads against the microservice redesign. Created in task T119. |
| [`COMPARISON.md`](COMPARISON.md) | Baseline versus redesign medians and the SC-009 verdicts (**MET** or **UNMET**). Created in task T120. |
| [`redesign/exploratory/`](redesign/exploratory/) | Single exploratory stress runs, **not** part of the comparison: `pool-10` (T118 pool sizing), `inventory-locking` and `inventory-locking-pool-20` (the Inventory locking fix), `current-code-2026-10-26` (normal and stress on the committed code), and `throughput-methods` (merged completion steps, binlog off, pool sizes 10–30), each with notes or reconciliation. |
| [`baseline/exploratory/pool-20/`](baseline/exploratory/pool-20/NOTES.md) | The baseline with only Checkout's pool raised to 20, used to separate the pool-size gain from the code gain. Not part of the comparison. |
| [`validation-2026-10-26/`](validation-2026-10-26/README.md) | Full quickstart validation V0–V11 from a reset (task T126). |
| [`historical/`](historical/) | The 2026-10-16 reports. **Historical — inadequate stock, not baseline evidence.** |

The workloads, provisioning and comparison method are described in
[research R14](../../../specs/004-domain-services/research.md#r14-baseline-and-performance-method-fr-030-fr-031-sc-009)
and [quickstart V10](../../../specs/004-domain-services/quickstart.md#v10-baseline-and-redesign-performance-us5-gate-4).
