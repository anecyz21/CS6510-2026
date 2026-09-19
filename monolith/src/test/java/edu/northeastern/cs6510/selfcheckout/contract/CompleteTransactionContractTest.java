package edu.northeastern.cs6510.selfcheckout.contract;

import edu.northeastern.cs6510.selfcheckout.api.ApiException;
import edu.northeastern.cs6510.selfcheckout.api.TransactionController;
import edu.northeastern.cs6510.selfcheckout.transaction.*;
import java.time.Instant;
import java.util.List;
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
class CompleteTransactionContractTest {
    @Autowired MockMvc mvc;
    @MockBean TransactionService service;
    @Test void completesAndMapsConflicts() throws Exception {
        Instant now = Instant.now();
        when(service.complete("tx-1")).thenReturn(new Receipt("tx-1", "station-1", 3, 555, now, now, List.of(new ReceiptLine("SKU-1", "Milk", 185, 3))));
        mvc.perform(post("/transactions/tx-1/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(3));
        when(service.complete("empty")).thenThrow(ApiException.emptyBasket("empty"));
        mvc.perform(post("/transactions/empty/complete")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("EMPTY_BASKET"));
    }
}
