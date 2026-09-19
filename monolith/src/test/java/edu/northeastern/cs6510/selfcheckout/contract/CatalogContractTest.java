package edu.northeastern.cs6510.selfcheckout.contract;

import edu.northeastern.cs6510.selfcheckout.api.CatalogController;
import edu.northeastern.cs6510.selfcheckout.catalog.CatalogItem;
import edu.northeastern.cs6510.selfcheckout.catalog.CatalogService;
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

@WebMvcTest(CatalogController.class)
class CatalogContractTest {
    @Autowired MockMvc mvc;
    @MockBean CatalogService catalog;
    @Test void returnsEveryCatalogItem() throws Exception {
        when(catalog.findAll()).thenReturn(List.of(new CatalogItem("SKU-000001", "Item 1", 185)));
        mvc.perform(get("/items")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sku").value("SKU-000001"))
                .andExpect(jsonPath("$.items[0].price").value(1.85));
    }
}
