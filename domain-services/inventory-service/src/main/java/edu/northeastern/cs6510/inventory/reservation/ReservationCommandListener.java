package edu.northeastern.cs6510.inventory.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryCommandV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReplyErrorV1;
import edu.northeastern.cs6510.contracts.inventory.v1.ReservationV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import edu.northeastern.cs6510.inventory.api.ApiException;
import edu.northeastern.cs6510.inventory.api.InternalApiException;
import edu.northeastern.cs6510.inventory.messaging.ReplyOutbox;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies reservation commands from {@code inventory.commands.v1} and answers each on
 * {@code inventory.replies.v1} (005 research R4, contracts/inventory-reservation-messages.md).
 *
 * <p>A successful command (a repeat included) writes its reply inside the reservation transaction.
 * A conflict or an invalid reserve changes nothing, so its {@code Conflict} reply is written in a
 * transaction of its own. The offset is committed only after that transaction commits (record ack
 * mode). A record that cannot be parsed, or of an unknown type, throws {@link IllegalArgumentException}
 * and is dead-lettered without a reply. Lock contention is retried by the error handler.
 */
@Component
public class ReservationCommandListener {
  public static final String GROUP = "inventory-reservations";

  private final ReservationService reservations;
  private final ReplyOutbox replies;
  private final ObjectMapper json;
  private final TransactionTemplate transactions;

  public ReservationCommandListener(ReservationService reservations, ReplyOutbox replies, ObjectMapper json,
                                    PlatformTransactionManager transactionManager) {
    this.reservations = reservations;
    this.replies = replies;
    this.json = json;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @KafkaListener(id = "reservation-commands", groupId = GROUP, topics = InventoryCommandV1.TOPIC,
      containerFactory = "commandListenerFactory")
  public void onCommand(ConsumerRecord<String, String> record) {
    Map<String, byte[]> headers = new HashMap<>();
    record.headers().forEach(h -> headers.put(h.key(), h.value()));
    Envelope envelope = Envelope.fromHeaders(headers);
    InventoryCommandV1 command = parse(record.value());
    if (!InventoryCommandV1.isKnownType(command.messageType())) {
      throw new IllegalArgumentException("unknown command type " + command.messageType());
    }
    if (command.attemptId() == null || command.attemptId().isBlank()) throw new IllegalArgumentException("command without attemptId");
    handle(command, envelope.messageId());
  }

  void handle(InventoryCommandV1 command, String causationId) {
    String attemptId = command.attemptId();
    String type = command.messageType();
    ReplySink sink = stored -> replies.write(attemptId, causationId, type, stored, null);
    try {
      switch (type) {
        case InventoryCommandV1.RESERVE_REQUESTED -> reservations.reserve(attemptId, command.reserveRequest(), sink);
        case InventoryCommandV1.COMMIT_REQUESTED -> reservations.commit(attemptId, sink);
        case InventoryCommandV1.CANCEL_REQUESTED -> reservations.cancel(attemptId, sink);
        default -> throw new IllegalArgumentException("unknown command type " + type);
      }
    } catch (InternalApiException conflict) {
      conflictReply(attemptId, causationId, type, conflict.body().reservation(),
          new ReplyErrorV1(conflict.body().error(), conflict.body().message()));
    } catch (ApiException failure) {
      if (failure.status() == HttpStatus.BAD_REQUEST) {
        conflictReply(attemptId, causationId, type, null, new ReplyErrorV1(failure.code(), failure.getMessage()));
      } else {
        // "Inventory is busy" after the in-transaction lock retries: transient, so the error handler retries.
        throw new TransientDataAccessResourceException(failure.getMessage(), failure);
      }
    }
  }

  private void conflictReply(String attemptId, String causationId, String command, ReservationV1 stored, ReplyErrorV1 error) {
    transactions.executeWithoutResult(status -> replies.write(attemptId, causationId, command, stored, error));
  }

  private InventoryCommandV1 parse(String value) {
    try {
      InventoryCommandV1 command = json.readValue(value, InventoryCommandV1.class);
      if (command == null) throw new IllegalArgumentException("empty command");
      return command;
    } catch (IOException e) {
      throw new IllegalArgumentException("unparseable command: " + e.getMessage(), e);
    }
  }
}
