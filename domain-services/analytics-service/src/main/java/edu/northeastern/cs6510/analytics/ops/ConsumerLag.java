package edu.northeastern.cs6510.analytics.ops;

import edu.northeastern.cs6510.analytics.ingest.KafkaConsumerConfig;
import edu.northeastern.cs6510.contracts.events.v1.AcceptedScanV1;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reads the ingest group's lag on the accepted-scans topic through an {@link AdminClient}. */
@Component
public class ConsumerLag implements DisposableBean {
  private static final long TIMEOUT_MS = 1000;

  private final AdminClient admin;

  public ConsumerLag(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    this.admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", (int) TIMEOUT_MS,
        "default.api.timeout.ms", (int) TIMEOUT_MS));
  }

  /**
   * End offset minus committed offset, summed over the topic's partitions (a partition the group never
   * committed counts from its earliest offset). Zero when the topic does not exist yet; null when the
   * broker cannot be reached within 1 s.
   */
  public Long lag() {
    try {
      TopicDescription topic;
      try {
        topic = admin.describeTopics(List.of(AcceptedScanV1.TOPIC)).topicNameValues().get(AcceptedScanV1.TOPIC)
            .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.ExecutionException e) {
        if (e.getCause() instanceof UnknownTopicOrPartitionException) return 0L;
        throw e;
      }
      Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
      Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
      topic.partitions().forEach(p -> {
        TopicPartition tp = new TopicPartition(AcceptedScanV1.TOPIC, p.partition());
        latest.put(tp, OffsetSpec.latest());
        earliest.put(tp, OffsetSpec.earliest());
      });
      Map<TopicPartition, ListOffsetsResultInfo> ends = admin.listOffsets(latest).all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      Map<TopicPartition, ListOffsetsResultInfo> starts = admin.listOffsets(earliest).all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(KafkaConsumerConfig.GROUP)
          .partitionsToOffsetAndMetadata().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      long lag = 0;
      for (Map.Entry<TopicPartition, ListOffsetsResultInfo> end : ends.entrySet()) {
        OffsetAndMetadata done = committed.get(end.getKey());
        long from = done != null ? done.offset() : starts.get(end.getKey()).offset();
        lag += Math.max(0, end.getValue().offset() - from);
      }
      return lag;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /** True when the broker answers a cluster description within 500 ms. */
  public boolean brokerReachable() {
    try {
      admin.describeCluster(new org.apache.kafka.clients.admin.DescribeClusterOptions().timeoutMs(500)).nodes()
          .get(1, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void destroy() { admin.close(); }
}
