package edu.northeastern.cs6510.checkout.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs6510.checkout.completion.CompletionSaga;
import edu.northeastern.cs6510.contracts.inventory.v1.InventoryReplyV1;
import edu.northeastern.cs6510.contracts.messaging.Envelope;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds Inventory's replies from {@code inventory.replies.v1} to {@link CompletionSaga} (005
 * research R5). Record ack mode: the offset is committed after the saga's local transaction. A
 * record that cannot be parsed throws {@link IllegalArgumentException} and is dead-lettered to
 * {@code checkout.dlt.v1}; a repeated reply is harmless because every step is a conditional update.
 */
@Component
public class ReplyListener {
  public static final String GROUP = "checkout-saga";

  private final CompletionSaga saga;
  private final ObjectMapper json;

  public ReplyListener(CompletionSaga saga, ObjectMapper json) {
    this.saga = saga;
    this.json = json;
  }

  @KafkaListener(id = "saga-replies", groupId = GROUP, topics = InventoryReplyV1.TOPIC, containerFactory = "replyListenerFactory")
  public void onReply(ConsumerRecord<String, String> record) {
    Map<String, byte[]> headers = new HashMap<>();
    record.headers().forEach(h -> headers.put(h.key(), h.value()));
    Envelope.fromHeaders(headers);
    InventoryReplyV1 reply;
    try {
      reply = json.readValue(record.value(), InventoryReplyV1.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("unparseable reply: " + e.getMessage(), e);
    }
    if (reply == null || reply.attemptId() == null || reply.messageType() == null) {
      throw new IllegalArgumentException("reply without attemptId or messageType");
    }
    saga.onReply(reply);
  }
}
