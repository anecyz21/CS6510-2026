package selfcheckout.domain;

import java.util.List;

public record CompletionResult(Receipt receipt, List<UnavailableItem> unavailableItems) {
    public CompletionResult {
        if (receipt == null || unavailableItems == null) {
            throw new IllegalArgumentException("Completion result requires receipt and unavailable items");
        }
        unavailableItems = List.copyOf(unavailableItems);
    }
}
