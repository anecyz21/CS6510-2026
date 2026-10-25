package edu.northeastern.cs6510.contracts.catalog.v1;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** {@code GET /internal/v1/catalog-version}: cheap freshness probe for catalog projections. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogVersionV1(long catalogVersion, Instant updatedAt, int activeItemCount) { }
