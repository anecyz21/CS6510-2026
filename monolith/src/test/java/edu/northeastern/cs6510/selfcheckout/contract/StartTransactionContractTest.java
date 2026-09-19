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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class StartTransactionContractTest {
    @Autowired MockMvc mvc;
    @MockBean TransactionService service;
    @Test void startsTransactionAndRejectsBlankStation() throws Exception {
        when(service.start("station-1")).thenReturn(new Transaction("tx-1", "station-1", TransactionStatus.OPEN, Map.of(), Map.of(), 0, 0, Instant.parse("2026-01-01T00:00:00Z"), null));
        mvc.perform(post("/transactions").contentType("application/json").content("{\"stationId\":\"station-1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
        when(service.start(" ")).thenThrow(ApiException.missingStationId());
        mvc.perform(post("/transactions").contentType("application/json").content("{\"stationId\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MISSING_STATION_ID"));
    }
}
