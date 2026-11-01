package edu.northeastern.cs6510.checkout.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryCommandV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReserveLineV1;
import edu.northeastern.cs6510.contracts.messaging.MessageIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code inventory.commands.v1} message to {@code command_outbox} (005 data-model). Must be
 * called inside the transaction of the attempt transition (or resend claim) that causes it;
 * {@link OutboxSender} publishes the row once that transaction commits. Every send, resends
 * included, gets a new message id; Inventory's idempotency key is the attempt id.
 */
@Component
public class CommandOutbox {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final OutboxSender sender;

  public CommandOutbox(JdbcTemplate db, ObjectMapper json, OutboxSender sender) {
    this.db = db;
    this.json = json;
    this.sender = sender;
  }

  public void reserve(String attemptId, String transactionId, List<ReserveLineV1> lines, Instant now) {
    write(InventoryCommandV1.reserve(MessageIds.next(), attemptId, transactionId, lines), now);
  }

  public void commit(String attemptId, Instant now) { write(InventoryCommandV1.commit(MessageIds.next(), attemptId), now); }

  public void cancel(String attemptId, Instant now) { write(InventoryCommandV1.cancel(MessageIds.next(), attemptId), now); }

  private void write(InventoryCommandV1 command, Instant now) {
    String payload;
    try {
      payload = json.writeValueAsString(command);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize " + command.messageType() + " for attempt " + command.attemptId(), e);
    }
    db.update("INSERT INTO command_outbox (message_id, attempt_id, message_type, payload, created_at) VALUES (?, ?, ?, ?, ?)",
        command.messageId(), command.attemptId(), command.messageType(), payload, Timestamp.from(now));
    sender.afterCommit(command.attemptId());
  }
}
