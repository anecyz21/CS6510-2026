package selfcheckout.domain;

public record PopularItem(String sku, String name, long scanCount, int rank) { }
