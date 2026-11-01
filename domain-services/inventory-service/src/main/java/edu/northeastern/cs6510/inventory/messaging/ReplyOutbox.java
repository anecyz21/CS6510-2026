package edu.northeastern.cs6510.inventory.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReplyErrorV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.messaging.MessageIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code inventory.replies.v1} message to {@code reply_outbox} (005 data-model). Must be
 * called inside the transaction of the reservation change the reply reports; {@link OutboxSender}
 * publishes the row after that transaction commits.
 */
@Component
public class ReplyOutbox {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final OutboxSender sender;

  public ReplyOutbox(JdbcTemplate db, ObjectMapper json, OutboxSender sender) {
    this.db = db;
    this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.sender = sender;
  }

  /** The reply to command {@code causationId} for {@code attemptId}: a stored state, or a conflict when {@code error} is set. */
  public void write(String attemptId, String causationId, String command, ReservationV1 reservation, ReplyErrorV1 error) {
    String messageId = MessageIds.next();
    String type = error != null ? InventoryReplyV1.CONFLICT : InventoryReplyV1.typeFor(reservation.state());
    InventoryReplyV1 reply = new InventoryReplyV1(InventoryReplyV1.SCHEMA_VERSION, messageId, type, attemptId, command, reservation, error);
    String payload;
    try {
      payload = json.writeValueAsString(reply);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialize the reply for attempt " + attemptId, e);
    }
    db.update("INSERT INTO reply_outbox (message_id, causation_id, attempt_id, message_type, payload, created_at) VALUES (?, ?, ?, ?, ?, ?)",
        messageId, causationId, attemptId, type, payload, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
    sender.afterCommit(attemptId);
  }
}
