package edu.northeastern.cs6510.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FullGatewayContractTest {
  @Test void openApiDocumentsAllSevenGatewayRoutes() throws Exception {
    String api=Files.readString(Path.of("..", "..", "spec/self-checkout-openapi.yaml"));
    for(String route:new String[]{"/items:","/transactions:","/transactions/{transactionId}/items:","/transactions/{transactionId}/complete:","/transactions/{transactionId}:","/inventory/low-stock:","/analytics/popular-items:"}) assertTrue(api.contains(route));
  }
}
