package edu.northeastern.cs6510.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.northeastern.cs6510.support.InventoryProvisioning;
import edu.northeastern.cs6510.support.PublicApi;
import edu.northeastern.cs6510.support.PublicApi.Response;
import edu.northeastern.cs6510.support.Stack;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Existing documented error behavior for missing, closed and empty transactions is preserved (US1-7). */
@Tag("system")
class CompletionPreconditionTest {
  private final PublicApi api = new PublicApi();

  @BeforeAll
  static void stackIsRunning() { Stack.requireRunning(); }

  @Test
  void completeOnUnknownTransactionIsNotFound() {
    assertError(api.complete("no-such-transaction"), 404, "TRANSACTION_NOT_FOUND");
  }

  @Test
  void completeOnEmptyBasketIsRejected() {
    String tx = api.start("precondition-empty").text("transactionId");
    assertError(api.complete(tx), 409, "EMPTY_BASKET");
  }

  @Test
  void completedTransactionRejectsCompletionAndScans() {
    InventoryProvisioning.setStock("SKU-000020", 5);
    String tx = api.start("precondition-closed").text("transactionId");
    assertEquals(200, api.scan(tx, "SKU-000020").status());
    assertEquals(200, api.complete(tx).status());
    assertError(api.complete(tx), 409, "TRANSACTION_NOT_OPEN");
    assertError(api.scan(tx, "SKU-000020"), 409, "TRANSACTION_NOT_OPEN");
  }

  @Test
  void scanOnUnknownTransactionIsNotFound() {
    assertError(api.scan("no-such-transaction", "SKU-000020"), 404, "TRANSACTION_NOT_FOUND");
  }

  @Test
  void scanOfUnknownSkuIsNotFoundWhileTheProjectionIsCurrent() {
    String tx = api.start("precondition-sku").text("transactionId");
    assertError(api.scan(tx, "SKU-999999"), 404, "SKU_NOT_FOUND");
  }

  /** Every error body has exactly the documented fields {@code error} and {@code message}. */
  private static void assertError(Response response, int status, String code) {
    assertEquals(status, response.status(), String.valueOf(response.body()));
    assertEquals(code, response.error());
    List<String> fields = new ArrayList<>();
    response.body().fieldNames().forEachRemaining(fields::add);
    assertEquals(List.of("error", "message"), fields.stream().sorted().toList());
    assertTrue(!response.text("message").isBlank());
  }
}
