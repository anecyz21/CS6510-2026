package edu.northeastern.cs6510.catalog.internal;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.northeastern.cs6510.catalog.CatalogRepository;
import edu.northeastern.cs6510.catalog.CatalogRepository.CatalogItem;
import edu.northeastern.cs6510.contracts.Fixtures;
import edu.northeastern.cs6510.contracts.catalog.v1.CatalogVersionV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductPageV1;
import edu.northeastern.cs6510.contracts.catalog.v1.ProductV1;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** Provider side of Catalog internal API v1: responses still carry every field of the v1 fixtures (R16). */
@WebMvcTest(CatalogInternalController.class)
class CatalogInternalContractTest {
  @Autowired MockMvc mvc;
  @MockBean CatalogRepository repository;

  @BeforeEach
  void catalog() {
    when(repository.version()).thenReturn(new CatalogRepository.CatalogVersion(1, Instant.parse("2026-10-24T12:00:00Z"), 2000));
    when(repository.activeItemsAfter(isNull(), anyInt())).thenReturn(List.of(
        new CatalogItem("SKU-000001", "Catalog item 1", 101), new CatalogItem("SKU-000002", "Catalog item 2", 102)));
    when(repository.activeItem("SKU-000123")).thenReturn(Optional.of(new CatalogItem("SKU-000123", "Catalog item 123", 223)));
    when(repository.activeItem("SKU-999999")).thenReturn(Optional.empty());
  }

  @Test
  void catalogVersionMatchesTheFixture() throws Exception {
    String body = mvc.perform(get("/internal/v1/catalog-version")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Fixtures.assertCovers(Fixtures.read("catalog-version-v1.json"), Fixtures.JSON.readTree(body), "catalog-version");
    Fixtures.JSON.readValue(body, CatalogVersionV1.class);
  }

  @Test
  void productPageMatchesTheFixture() throws Exception {
    String body = mvc.perform(get("/internal/v1/products").param("limit", "1")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    Fixtures.assertCovers(Fixtures.read("product-page-v1.json"), Fixtures.JSON.readTree(body), "products");
    Fixtures.JSON.readValue(body, ProductPageV1.class);
  }

  @Test
  void productMatchesTheFixtureAndUnknownSkusAreNotFound() throws Exception {
    String body = mvc.perform(get("/internal/v1/products/SKU-000123")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    Fixtures.assertCovers(Fixtures.read("product-v1.json"), Fixtures.JSON.readTree(body), "product");
    Fixtures.JSON.readValue(body, ProductV1.class);
    mvc.perform(get("/internal/v1/products/SKU-999999")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("SKU_NOT_FOUND"));
  }
}
