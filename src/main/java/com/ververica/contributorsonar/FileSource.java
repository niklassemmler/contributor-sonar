package com.ververica.contributorsonar;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.util.function.ThrowingConsumer;

import javax.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public class FileSource<T extends WithEventTime> extends RichSourceFunction<T>
    implements SourceFunction<T>, Serializable {

  @Nullable private Instant lastEventTime;
  private final int servingSpeed;

  private final Deserializer<T> deserializer;
  private final String dataFilePath;

  private BufferedReader reader;

  private final ThrowingConsumer<Long, InterruptedException> sleepInMs;

  public FileSource(
      String dataFilePath,
      Deserializer<T> deserializer,
      @Nullable Instant startTime,
      int servingSpeedFactor) {
    this(dataFilePath, deserializer, startTime, servingSpeedFactor, Thread::sleep);
  }

  public FileSource(
      String dataFilePath,
      Deserializer<T> deserializer,
      @Nullable Instant startTime,
      int servingSpeedFactor,
      ThrowingConsumer<Long, InterruptedException> sleepInMs) {
    this.deserializer = deserializer;
    this.dataFilePath = dataFilePath;
    this.lastEventTime = startTime;
    this.servingSpeed = servingSpeedFactor;
    this.sleepInMs = sleepInMs;
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    this.reader = new BufferedReader(new InputStreamReader(new FileInputStream(dataFilePath)));
    super.open(parameters);
  }

  @Override
  public void run(SourceContext<T> sourceContext) throws Exception {
    String line = reader.readLine();
    while (line != null) {
      final T entity = deserializer.deserialize(line);
      initializeStart(entity);
      if (entity.getEventTime().isBefore(Objects.requireNonNull(lastEventTime))) {
        continue;
      }

      final long waitTime =
          Duration.between(lastEventTime, entity.getEventTime()).toMillis() / servingSpeed;
      sleepInMs.accept(waitTime);

      sourceContext.collect(entity);

      lastEventTime = entity.getEventTime();

      line = reader.readLine();
    }
  }

  private void initializeStart(T entity) {
    if (this.lastEventTime == null) {
      this.lastEventTime = entity.getEventTime();
    }
  }

  @Override
  public void cancel() {
    try {
      if (this.reader != null) {
        this.reader.close();
      }
    } catch (IOException ioe) {
      throw new RuntimeException("Could not cancel SourceFunction", ioe);
    } finally {
      this.reader = null;
    }
  }
}
