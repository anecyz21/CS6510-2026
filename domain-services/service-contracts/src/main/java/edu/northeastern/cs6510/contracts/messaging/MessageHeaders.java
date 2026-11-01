package edu.northeastern.cs6510.contracts.messaging;

/**
 * Kafka header names shared by every message between domain services (005 contracts/README.md,
 * "Envelope"), and the headers added when a record is moved to a dead-letter topic.
 */
public final class MessageHeaders {
  public static final String MESSAGE_ID = "messageId";
  public static final String MESSAGE_TYPE = "messageType";
  public static final String SCHEMA_VERSION = "schemaVersion";
  public static final String PRODUCER = "producer";
  public static final String OCCURRED_AT = "occurredAt";
  /** The attempt id on inventory commands and replies. */
  public static final String CORRELATION_ID = "correlationId";
  /** On a reply: the messageId of the command it answers. */
  public static final String CAUSATION_ID = "causationId";

  public static final String DLT_ORIGINAL_TOPIC = "dlt-original-topic";
  public static final String DLT_ORIGINAL_PARTITION = "dlt-original-partition";
  public static final String DLT_ORIGINAL_OFFSET = "dlt-original-offset";
  public static final String DLT_EXCEPTION_CLASS = "dlt-exception-class";
  public static final String DLT_EXCEPTION_MESSAGE = "dlt-exception-message";

  private MessageHeaders() { }
}
