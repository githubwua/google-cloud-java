/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsub;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implementation of {@link Subscriber}. */
public class SubscriberImpl extends AbstractService implements Subscriber {
  private static final int THREADS_PER_CHANNEL = 5;
  @VisibleForTesting static final int CHANNELS_PER_CORE = 10;
  private static final int MAX_INBOUND_MESSAGE_SIZE =
      20 * 1024 * 1024; // 20MB API maximum message size.
  private static final int INITIAL_ACK_DEADLINE_SECONDS = 10;
  private static final int MAX_ACK_DEADLINE_SECONDS = 600;
  private static final int MIN_ACK_DEADLINE_SECONDS = 10;
  private static final Duration ACK_DEADLINE_UPDATE_PERIOD = Duration.standardMinutes(1);
  private static final double PERCENTILE_FOR_ACK_DEADLINE_UPDATES = 99.9;

  private static final Logger logger = LoggerFactory.getLogger(SubscriberImpl.class);

  private final String subscription;
  private final Optional<Integer> maxOutstandingBytes;
  private final Optional<Integer> maxOutstandingMessages;
  private final Duration ackExpirationPadding;
  private final SubscriberConnection[] subscriberConnections;
  private final ScheduledExecutorService executor;
  private final Distribution ackLatencyDistribution =
      new Distribution(MAX_ACK_DEADLINE_SECONDS + 1);
  private ScheduledFuture<?> ackDeadlineUpdater;
  private int streamAckDeadlineSeconds;

  public SubscriberImpl(SubscriberImpl.Builder builder) throws IOException {
    maxOutstandingBytes = builder.maxOutstandingBytes;
    maxOutstandingMessages = builder.maxOutstandingMessages;
    subscription = builder.subscription;
    ackExpirationPadding = builder.ackExpirationPadding;
    streamAckDeadlineSeconds =
        Math.max(
            INITIAL_ACK_DEADLINE_SECONDS,
            Ints.saturatedCast(ackExpirationPadding.getStandardSeconds()));

    int numChannels = Math.max(1, Runtime.getRuntime().availableProcessors()) * CHANNELS_PER_CORE;
    executor =
        builder.executor.isPresent()
            ? builder.executor.get()
            : Executors.newScheduledThreadPool(
                numChannels * THREADS_PER_CHANNEL,
                new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("cloud-pubsub-subscriber-thread-%d")
                    .build());
    ManagedChannelBuilder<? extends ManagedChannelBuilder<?>> channelBuilder =
        builder.channelBuilder.isPresent()
            ? builder.channelBuilder.get()
            : NettyChannelBuilder.forAddress(PUBSUB_API_ADDRESS, 443)
                .maxMessageSize(MAX_INBOUND_MESSAGE_SIZE)
                .flowControlWindow(5000000) // 2.5 MB
                .negotiationType(NegotiationType.TLS)
                .sslContext(GrpcSslContexts.forClient().ciphers(null).build())
                .executor(executor);

    Credentials credentials =
        builder.credentials.isPresent()
            ? builder.credentials.get()
            : GoogleCredentials.getApplicationDefault()
                .createScoped(Collections.singletonList(PUBSUB_API_SCOPE));

    FlowController flowController =
        new FlowController(builder.maxOutstandingBytes, builder.maxOutstandingBytes, false);
    subscriberConnections = new SubscriberConnection[numChannels];
    for (int i = 0; i < subscriberConnections.length; i++) {
      subscriberConnections[i] =
          new SubscriberConnection(
              subscription,
              credentials,
              builder.receiver,
              ackExpirationPadding,
              streamAckDeadlineSeconds,
              ackLatencyDistribution,
              channelBuilder.build(),
              flowController,
              executor);
    }
  }

  @Override
  protected void doStart() {
    logger.debug("Starting subscriber group.");

    final CountDownLatch subscribersStarting = new CountDownLatch(subscriberConnections.length);
    for (final SubscriberConnection subscriber : subscriberConnections) {
      executor.submit(
          new Runnable() {
            @Override
            public void run() {
              subscriber.startAsync().awaitRunning();
              subscribersStarting.countDown();
              subscriber.addListener(
                  new Listener() {
                    @Override
                    public void failed(State from, Throwable failure) {
                      // If a connection failed is because of a fatal error, we should fail the
                      // whole subscriber.
                      stopAllConnections();
                      notifyFailed(failure);
                    }
                  },
                  executor);
            }
          });
    }
    try {
      subscribersStarting.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    ackDeadlineUpdater =
        executor.scheduleAtFixedRate(
            new Runnable() {
              @Override
              public void run() {
                // It is guaranteed this will be <= MAX_ACK_DEADLINE_SECONDS, the max of the API.
                long ackLatency =
                    ackLatencyDistribution.getNthPercentile(PERCENTILE_FOR_ACK_DEADLINE_UPDATES);
                if (ackLatency > 0) {
                  int possibleStreamAckDeadlineSeconds =
                      Math.max(
                          MIN_ACK_DEADLINE_SECONDS,
                          Ints.saturatedCast(
                              Math.max(ackLatency, ackExpirationPadding.getStandardSeconds())));
                  if (streamAckDeadlineSeconds != possibleStreamAckDeadlineSeconds) {
                    streamAckDeadlineSeconds = possibleStreamAckDeadlineSeconds;
                    logger.debug(
                        "Updating stream deadline to {} seconds.", streamAckDeadlineSeconds);
                    for (SubscriberConnection subscriberConnection : subscriberConnections) {
                      subscriberConnection.updateStreamAckDeadline(streamAckDeadlineSeconds);
                    }
                  }
                }
              }
            },
            ACK_DEADLINE_UPDATE_PERIOD.getMillis(),
            ACK_DEADLINE_UPDATE_PERIOD.getMillis(),
            TimeUnit.MILLISECONDS);
    notifyStarted();
  }

  @Override
  protected void doStop() {
    ackDeadlineUpdater.cancel(true);
    stopAllConnections();
    notifyStopped();
  }

  private void stopAllConnections() {
    final CountDownLatch connectionsStopping = new CountDownLatch(subscriberConnections.length);
    for (final SubscriberConnection subscriberConnection : subscriberConnections) {
      executor.submit(
          new Runnable() {
            @Override
            public void run() {
              try {
                subscriberConnection.stopAsync().awaitTerminated();
              } catch (IllegalStateException ignored) {
                // It is expected for some connections to be already in state failed so stop will
                // throw this expection.
              }
              connectionsStopping.countDown();
            }
          });
    }
    try {
      connectionsStopping.await();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public SubscriberStats getStats() {
    // TODO: Implement me
    return null;
  }

  @Override
  public String getSubscription() {
    return subscription;
  }

  @Override
  public Duration getAckExpirationPadding() {
    return ackExpirationPadding;
  }

  @Override
  public Optional<Integer> getMaxOutstandingMessages() {
    return maxOutstandingMessages;
  }

  @Override
  public Optional<Integer> getMaxOutstandingBytes() {
    return maxOutstandingBytes;
  }
}
