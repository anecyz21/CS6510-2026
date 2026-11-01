package edu.northeastern.cs6510.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import edu.northeastern.cs6510.support.Faults;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.Reconciliation;
import edu.northeastern.cs6510.support.Stack;
import edu.northeastern.cs6510.support.Workload;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 005 quickstart Q4, SC-007: with every outbox sender and the scan relay sending each record twice,
 * stock, receipts and Analytics counts still reconcile exactly. Checkout's metric shows that it saw
 * the duplicate replies and ignored them.
 */
@Tag("system")
class DuplicateDeliveryTest {
  private static final Map<String, String> DUPLICATES = Map.of("MESSAGING_FAULTS_DUPLICATE_SENDS", "true");

  @BeforeAll
  static void duplicateEverySend() {
    Stack.requireRunning();
    Faults.requireFaultsStack();
    Stack.recreate("inventory", DUPLICATES);
    Stack.recreate("checkout", DUPLICATES);
  }

  @AfterAll
  static void restore() {
    Stack.recreate("inventory", Map.of("MESSAGING_FAULTS_DUPLICATE_SENDS", "false"));
    Stack.recreate("checkout", Map.of("MESSAGING_FAULTS_DUPLICATE_SENDS", "false"));
  }

  @Test
  void everyMessageDeliveredTwiceHasOneEffect() throws Exception {
    Workload workload = Workload.start(4, 3, "duplicates");
    Thread.sleep(30_000);
    workload.close();

    assertTrue(workload.count("COMPLETE 200") > 0, "purchases completed: " + workload.statuses());
    assertEquals(0, workload.statuses().keySet().stream().filter(k -> !k.endsWith(" 200") && !k.endsWith(" 201")).count(),
        "no public error while every message is duplicated: " + workload.statuses());
    Reconciliation.assertPass();

    JsonNode ignored = new PublicApi(Stack.SERVICE_URLS.get("checkout")).get("/actuator/metrics/checkout.saga.reply.ignored").body();
    assertTrue(ignored.get("measurements").get(0).get("value").asDouble() > 0, "the duplicate replies reached Checkout and were ignored");
  }
}
