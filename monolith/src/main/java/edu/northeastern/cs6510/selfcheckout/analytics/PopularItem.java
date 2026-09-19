package edu.northeastern.cs6510.selfcheckout.analytics;
public record PopularItem(String sku, String name, int scanCount, int rank) { }
