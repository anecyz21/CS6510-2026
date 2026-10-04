# Research: Windowed Analytics Pipeline

## Decisions

### Three ordered filters with FIFO pipes

**Decision**: Use three named filters: `ingest` → `window` → `rank-and-publish`, joined by FIFO
blocking queues.

**Rationale**: The existing `AnalyticsService` performs sequencing, window retention, counting,
ranking, and publication in one synchronized method. Separating these concerns satisfies the
filter-and-pipe requirement and removes ranking work from request-handling threads.

**Alternatives considered**: Keeping one synchronized service does not meet the assignment.
Separate deployable services or a message broker add infrastructure without being required.

### One worker per filter, immutable handoffs

**Decision**: Run one dedicated Java platform thread per filter. `ingest` assigns contiguous
sequence numbers; `window` owns the mutable 1,000-item deque and emits immutable updates on scan
1 and every 500 scans; `rank-and-publish` ranks an immutable copy and atomically replaces the
latest immutable snapshot.

**Rationale**: One worker per stage preserves global order without synchronizing mutable window
state. Atomic snapshot publication means readers see only a complete prior or next ranking.

**Alternatives considered**: Multiple consumers need partitioning or reordering. Sharing a mutable
deque with ranking can expose inconsistent bounds or counts.

### Unbounded in-memory queues for accepted scans

**Decision**: Use unbounded `LinkedBlockingQueue` pipes for this in-memory assignment workload.

**Rationale**: A successful scan becomes a fast enqueue operation, preventing slow ranking from
blocking the synchronous checkout path. A non-blocking drop policy is unacceptable because accepted
scans must not be lost.

**Alternatives considered**: Bounded queues limit memory but can block checkout requests during a
backlog. Dropping work violates the accepted-scan guarantee.

### Ordered shutdown drain

**Decision**: Make analytics closeable. Stop new intake, append a terminal message, forward that
message only after each filter finishes prior work, then join workers and surface failures.

**Rationale**: FIFO terminal messages ensure each accepted scan reaches its eligible filter before
normal shutdown completes.

**Alternatives considered**: Interrupting workers or using daemon threads can discard queued work.

### Required workload evidence

**Decision**: Add a newly generated default report and a `--stations=100 --duration=120` report to
`layered-server/reports`, and name both in its README.

**Rationale**: Existing historical stress reports use 200 stations for 180 seconds and cannot
substitute for the required 100-station/120-second run. Historical artifacts stay intact.

**Alternatives considered**: Reusing the older stress report fails the required configuration;
deleting historical reports is unnecessary and destructive.
