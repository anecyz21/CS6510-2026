package edu.northeastern.cs6510.selfcheckout.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;

/** Serializes a minor-unit amount as a JSON number with two decimal places. */
public final class MoneySerialization extends JsonSerializer<Long> {
    public static BigDecimal asDecimal(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    @Override
    public void serialize(Long cents, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeNumber(asDecimal(cents));
    }
}
