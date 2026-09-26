package selfcheckout.domain;

public record ReceiptLine(String sku, String name, double unitPrice, int quantity) { }
