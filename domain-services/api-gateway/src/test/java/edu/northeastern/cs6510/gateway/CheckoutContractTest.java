package edu.northeastern.cs6510.gateway;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(CheckoutRouteHandler.class)
class CheckoutContractTest {
  @Autowired MockMvc mvc;
  @MockBean CheckoutClient checkout;

  @Test void preservesCheckoutRouteShapesAndValidation() throws Exception {
    when(checkout.start("station-1")).thenReturn(Map.of("transactionId", "tx-1", "status", "OPEN"));
    when(checkout.scan("tx-1", "SKU-1")).thenReturn(Map.of("transactionId", "tx-1", "sku", "SKU-1", "itemCount", 1));
    when(checkout.get("tx-1")).thenReturn(Map.of("transactionId", "tx-1", "status", "OPEN"));
    when(checkout.complete("tx-1")).thenReturn(Map.of("transactionId", "tx-1", "lines", new Object[0]));
    when(checkout.scan("missing", "SKU-1")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND"));
    when(checkout.complete("empty")).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "EMPTY_BASKET"));
    mvc.perform(post("/transactions").contentType("application/json").content("{\"stationId\":\"station-1\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
    mvc.perform(post("/transactions/tx-1/items").contentType("application/json").content("{\"sku\":\"SKU-1\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1));
    mvc.perform(get("/transactions/tx-1")).andExpect(status().isOk()).andExpect(jsonPath("$.transactionId").value("tx-1"));
    mvc.perform(post("/transactions/tx-1/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.lines").isArray());
    mvc.perform(post("/transactions").contentType("application/json").content("{\"stationId\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/transactions/missing/items").contentType("application/json").content("{\"sku\":\"SKU-1\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/transactions/empty/complete")).andExpect(status().isConflict());
  }
}
