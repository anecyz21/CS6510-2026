package edu.northeastern.cs6510.selfcheckout.api.dto;

import java.util.List;

public record CatalogResponse(List<CatalogItemResponse> items) { }
