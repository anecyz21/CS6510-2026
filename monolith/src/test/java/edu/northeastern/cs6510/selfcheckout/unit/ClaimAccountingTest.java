package edu.northeastern.cs6510.selfcheckout.unit;

import edu.northeastern.cs6510.selfcheckout.inventory.SkuInventory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimAccountingTest {
    @Test void permitsOnlyClaimsBackedByPhysicalStock() {
        assertDoesNotThrow(() -> new SkuInventory("SKU-1", 10, 5, 5, null));
        assertThrows(IllegalArgumentException.class, () -> new SkuInventory("SKU-1", 10, 5, 6, null));
    }
}
