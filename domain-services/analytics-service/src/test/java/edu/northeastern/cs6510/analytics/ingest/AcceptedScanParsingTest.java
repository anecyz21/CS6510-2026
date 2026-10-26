package edu.northeastern.cs6510.analytics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Consumer side of the accepted-scan fixture (T099): Analytics reads v1 and tolerates added fields. */
class AcceptedScanParsingTest {
  private final AcceptedScanListener listener = new AcceptedScanListener(null, null, new NoTransactions(),
      Jackson2ObjectMapperBuilder.json().build(), new SimpleMeterRegistry());

  @Test
  void parsesTheFixture() throws Exception {
    AcceptedScanV1 event = listener.parse(Fixtures.read("accepted-scan-v1.json").toString());
    assertEquals(1, event.schemaVersion());
    assertEquals("0192c3f4-8b7a-7cc1-9a51-2f0e5d6c1a90", event.eventId());
    assertEquals("AcceptedScan", event.eventType());
    assertEquals("7b1e2c94-5d3a-4f6e-9b21-0c8d7e6f5a43", event.transactionId());
    assertEquals("station-17", event.stationId());
    assertEquals("SKU-000001", event.sku());
    assertEquals(Instant.parse("2026-10-24T12:00:00.123456Z"), event.acceptedAt());
    assertEquals(18342, event.outboxId());
    assertNull(AcceptedScanListener.invalid(event));
  }

  @Test
  void parsesTheFixtureWithAnUnknownField() throws Exception {
    AcceptedScanV1 event = listener.parse(Fixtures.withUnknownField("accepted-scan-v1.json"));
    assertEquals("SKU-000001", event.sku());
    assertNull(AcceptedScanListener.invalid(event));
  }

  @Test
  void aNewerSchemaVersionIsRejected() throws Exception {
    String v2 = Fixtures.read("accepted-scan-v1.json").toString().replace("\"schemaVersion\":1", "\"schemaVersion\":2");
    assertNotNull(AcceptedScanListener.invalid(listener.parse(v2)));
  }

  /** The parsing path never opens a transaction. */
  private static final class NoTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
    @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
    @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
  }
}
