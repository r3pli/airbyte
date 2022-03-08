/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.buffered_stream_consumer;

import io.airbyte.commons.concurrency.VoidCallable;
import io.airbyte.commons.functional.CheckedBiConsumer;
import io.airbyte.commons.string.Strings;
import io.airbyte.integrations.base.AirbyteStreamNameNamespacePair;
import io.airbyte.integrations.destination.buffered_stream_consumer.RecordBufferImplementation.RecordBufferSettings;
import io.airbyte.protocol.models.AirbyteMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerializedRecordBufferingStrategy implements RecordBufferingStrategy {

  private static final Logger LOGGER = LoggerFactory.getLogger(SerializedRecordBufferingStrategy.class);

  private final RecordBufferSettings bufferSettings;
  private final CheckedBiConsumer<AirbyteStreamNameNamespacePair, RecordBufferImplementation, Exception> onStreamFlush;
  private VoidCallable onFlushEventHook;

  private Map<AirbyteStreamNameNamespacePair, RecordBufferImplementation> allBuffers = new HashMap<>();
  private long totalBufferSizeInBytes;

  public SerializedRecordBufferingStrategy(final RecordBufferSettings bufferSettings,
                                           final CheckedBiConsumer<AirbyteStreamNameNamespacePair, RecordBufferImplementation, Exception> onStreamFlush) {
    this.bufferSettings = bufferSettings;
    this.onStreamFlush = onStreamFlush;
    this.totalBufferSizeInBytes = 0;
    this.onFlushEventHook = null;
  }

  @Override
  public void registerFlushEventHook(final VoidCallable onFlushEventHook) {
    this.onFlushEventHook = onFlushEventHook;
  }

  @Override
  public void addRecord(final AirbyteStreamNameNamespacePair stream, final AirbyteMessage message) throws Exception {

    final RecordBufferImplementation streamBuffer = allBuffers.computeIfAbsent(stream, k -> {
      LOGGER.info("Starting a new buffer for stream {} (current state: {} in {} buffers)",
          stream.getName(),
          FileUtils.byteCountToDisplaySize(totalBufferSizeInBytes),
          allBuffers.size());
      try {
        return bufferSettings.newInstance();
      } catch (Exception e) {
        LOGGER.error("Failed to create a new buffer for stream {}", stream.getName(), e);
        throw new RuntimeException(e);
      }
    });
    if (streamBuffer != null) {
      final Long actualMessageSizeInBytes = streamBuffer.accept(message.getRecord());
      totalBufferSizeInBytes += actualMessageSizeInBytes;
      if (totalBufferSizeInBytes > bufferSettings.getMaxTotalBufferSizeInBytes()
          || allBuffers.size() > bufferSettings.getMaxConcurrentStreamsInBuffer()) {
        flushAll();
        totalBufferSizeInBytes = 0;
      } else if (streamBuffer.getCount() > bufferSettings.getMaxPerStreamBufferSizeInBytes()) {
        flushWriter(stream, streamBuffer);
      }
    }
  }

  @Override
  public void flushWriter(final AirbyteStreamNameNamespacePair stream, final RecordBufferImplementation writer) throws Exception {
    LOGGER.info("Flushing buffer of stream {} ({})", stream.getName(), FileUtils.byteCountToDisplaySize(writer.getCount()));
    onStreamFlush.accept(stream, writer);
    totalBufferSizeInBytes -= writer.getCount();
    allBuffers.remove(stream);
  }

  @Override
  public void flushAll() throws Exception {
    LOGGER.info("Flushing all {} current buffers ({} in total)", allBuffers.size(), FileUtils.byteCountToDisplaySize(totalBufferSizeInBytes));

    for (final Entry<AirbyteStreamNameNamespacePair, RecordBufferImplementation> entry : allBuffers.entrySet()) {
      LOGGER.info("Flushing buffer of stream {} ({})", entry.getKey().getName(), FileUtils.byteCountToDisplaySize(entry.getValue().getCount()));
      onStreamFlush.accept(entry.getKey(), entry.getValue());
    }
    close();
    clear();

    if (onFlushEventHook != null) {
      onFlushEventHook.call();
    }
    totalBufferSizeInBytes = 0;
  }

  @Override
  public void clear() throws Exception {
    LOGGER.debug("Reset all buffers");
    allBuffers = new HashMap<>();
  }

  @Override
  public void close() throws Exception {
    final List<Exception> exceptionsThrown = new ArrayList<>();
    for (final Entry<AirbyteStreamNameNamespacePair, RecordBufferImplementation> entry : allBuffers.entrySet()) {
      try {
        LOGGER.info("Closing buffer for stream {}", entry.getKey().getName());
        entry.getValue().close();
      } catch (Exception e) {
        exceptionsThrown.add(e);
        LOGGER.error("Exception while closing stream buffer", e);
      }
    }
    if (!exceptionsThrown.isEmpty()) {
      throw new RuntimeException(String.format("Exceptions thrown while closing buffers: %s", Strings.join(exceptionsThrown, "\n")));
    }
  }

}
