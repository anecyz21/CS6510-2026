package edu.northeastern.cs6510.selfcheckout.contract;

import edu.northeastern.cs6510.selfcheckout.api.InventoryController;
import edu.northeastern.cs6510.selfcheckout.inventory.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
class LowStockContractTest {
    @Autowired MockMvc mvc;
    @MockBean LowStockService service;
    @Test void returnsOnlyStrictlyLowStockAndHonorsQueryThreshold() throws Exception {
        Instant now = Instant.now();
        when(service.getLowStock(null)).thenReturn(new LowStockResult(50, now, List.of(new LowStockAlert("SKU-1", "Milk", 49, 50, now))));
        mvc.perform(get("/inventory/low-stock")).andExpect(status().isOk()).andExpect(jsonPath("$.threshold").value(50)).andExpect(jsonPath("$.alerts[0].currentStock").value(49));
        when(service.getLowStock(0)).thenReturn(new LowStockResult(0, now, List.of()));
        mvc.perform(get("/inventory/low-stock?threshold=0")).andExpect(status().isOk()).andExpect(jsonPath("$.alerts").isEmpty());
    }
}
