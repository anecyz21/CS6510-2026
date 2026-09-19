package edu.northeastern.cs6510.selfcheckout.unit;

import edu.northeastern.cs6510.selfcheckout.transaction.ReceiptLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiptAggregationTest {
    @Test void aReceiptLineRepresentsThreeScansExactly() {
        List<ReceiptLine> lines = List.of(new ReceiptLine("SKU-1", "Milk", 185, 3));
        assertEquals(3, lines.stream().mapToInt(ReceiptLine::quantity).sum());
        assertEquals(555, lines.stream().mapToLong(line -> line.unitPriceCents() * line.quantity()).sum());
    }
}
