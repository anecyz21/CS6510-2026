package selfcheckout.domain;

import java.util.Objects;

public record CatalogItem(String sku, String name, double price) {
    public CatalogItem {
        if (sku == null || sku.isBlank() || name == null || name.isBlank() || price < 0) {
            throw new IllegalArgumentException("Catalog item requires SKU, name, and non-negative price");
        }
        Objects.requireNonNull(sku);
    }
}
