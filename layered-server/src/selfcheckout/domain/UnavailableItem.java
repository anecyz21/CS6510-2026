package selfcheckout.domain;

public record UnavailableItem(String sku, String name, int quantity) {
    public UnavailableItem {
        if (sku == null || sku.isBlank() || name == null || name.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("Unavailable item requires SKU, name, and positive quantity");
        }
    }
}
