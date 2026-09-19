package edu.northeastern.cs6510.selfcheckout.contract;

import edu.northeastern.cs6510.selfcheckout.api.ApiException;
import edu.northeastern.cs6510.selfcheckout.api.TransactionController;
import edu.northeastern.cs6510.selfcheckout.transaction.TransactionService;
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
class InsufficientStockContractTest {
    @Autowired MockMvc mvc;
    @MockBean TransactionService service;
    @Test void mapsInsufficientStockToAStableConflict() throws Exception {
        when(service.complete("tx-short")).thenThrow(ApiException.insufficientStock("tx-short"));
        mvc.perform(post("/transactions/tx-short/complete")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));
    }
}
