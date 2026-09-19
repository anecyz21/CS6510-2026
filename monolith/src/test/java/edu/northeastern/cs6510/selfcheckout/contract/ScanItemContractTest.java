package edu.northeastern.cs6510.selfcheckout.contract;

import edu.northeastern.cs6510.selfcheckout.api.ApiException;
import edu.northeastern.cs6510.selfcheckout.api.TransactionController;
import edu.northeastern.cs6510.selfcheckout.transaction.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class ScanItemContractTest {
    @Autowired MockMvc mvc;
    @MockBean TransactionService service;
    @Test void scansAndMapsFailures() throws Exception {
        Transaction transaction = new Transaction("tx-1", "station-1", TransactionStatus.OPEN, Map.of("SKU-1", 1), Map.of("SKU-1", 1), 1, 185, Instant.now(), null);
        when(service.scan("tx-1", "SKU-1")).thenReturn(new ScanResult(transaction, "SKU-1", "Milk", 185));
        mvc.perform(post("/transactions/tx-1/items").contentType("application/json").content("{\"sku\":\"SKU-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.itemCount").value(1)).andExpect(jsonPath("$.runningTotal").value(1.85));
        when(service.scan("missing", "SKU-1")).thenThrow(ApiException.transactionNotFound("missing"));
        mvc.perform(post("/transactions/missing/items").contentType("application/json").content("{\"sku\":\"SKU-1\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("TRANSACTION_NOT_FOUND"));
    }
}
