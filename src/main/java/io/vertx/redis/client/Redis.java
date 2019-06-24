/*
 * Copyright 2019 Red Hat, Inc.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 * <p>
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * <p>
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 * <p>
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.redis.client;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.*;
import io.vertx.core.streams.ReadStream;
import io.vertx.redis.client.impl.RedisClient;
import io.vertx.redis.client.impl.RedisClusterClient;
import io.vertx.redis.client.impl.RedisSentinelClient;

import java.util.List;

/**
 * A simple Redis client.
 */
@VertxGen
public interface Redis extends ReadStream<Response> {

  /**
   * Connect to redis, the {@code onConnect} will get the {@link Redis} instance.
   *
   * This connection will use the default options which are connect
   * to a standalone server on the default port on "localhost".
   */
  static Redis createClient(Vertx vertx, String address) {
    return createClient(vertx, new RedisOptions().setEndpoint(address));
  }

  /**
   * Connect to redis, the {@code onConnect} will get the {@link Redis} instance.
   */
  static Redis createClient(Vertx vertx, RedisOptions options) {
    switch (options.getType()) {
      case STANDALONE:
        return RedisClient.create(vertx, options);
      case SENTINEL:
        return RedisSentinelClient.create(vertx, options);
      case CLUSTER:
        return RedisClusterClient.create(vertx, options);
      default:
        throw new IllegalStateException("Unknown Redis Client type: " + options.getType());
    }
  }

  /**
   * Connects to the redis server.
   *
   * @param handler  the async result handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis connect(Handler<AsyncResult<Redis>> handler);

  /**
   * Connects to the redis server.
   *
   * @return a future with the result of the operation
   */
  default Future<Redis> connect() {
    final Promise<Redis> promise = Promise.promise();
    connect(promise);
    return promise.future();
  }

  /**
   * Set an exception handler on the read stream.
   *
   * @param handler  the exception handler
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis exceptionHandler(Handler<Throwable> handler);

  /**
   * Set a data handler. As data is read, the handler will be called with the data.
   *
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis handler(Handler<Response> handler);

  /**
   * Pause the {@code ReadStream}, it sets the buffer in {@code fetch} mode and clears the actual demand.
   * <p>
   * While it's paused, no data will be sent to the data {@code handler}.
   *
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis pause();

  /**
   * Resume reading, and sets the buffer in {@code flowing} mode.
   * <p/>
   * If the {@code ReadStream} has been paused, reading will recommence on it.
   *
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis resume();

  /**
   * Fetch the specified {@code amount} of elements. If the {@code ReadStream} has been paused, reading will
   * recommence with the specified {@code amount} of items, otherwise the specified {@code amount} will
   * be added to the current stream demand.
   *
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis fetch(long amount);

  /**
   * Set an end handler. Once the stream has ended, and there is no more data to be read, this handler will be called.
   *
   * @return a reference to this, so the API can be used fluently
   */
  @Fluent
  Redis endHandler(@Nullable Handler<Void> endHandler);


  /**
   * Send the given command to the redis server or cluster.
   * @param command the command to send
   * @param onSend the asynchronous result handler.
   * @return fluent self.
   */
  @Fluent
  Redis send(Request command, Handler<AsyncResult<@Nullable Response>> onSend);

  /**
   * Send the given command to the redis server or cluster.
   * @param command the command to send
   * @return a future with the result of the operation
   */
  default Future<@Nullable Response> send(Request command) {
    final Promise<@Nullable Response> promise = Promise.promise();
    send(command, promise);
    return promise.future();
  }

  /**
   * Sends a list of commands in a single IO operation, this prevents any inter twinning to happen from other
   * client users.
   *
   * @param commands list of command to send
   * @param onSend the asynchronous result handler.
   * @return fluent self.
   */
  @Fluent
  Redis batch(List<Request> commands, Handler<AsyncResult<List<@Nullable Response>>> onSend);

  /**
   * Sends a list of commands in a single IO operation, this prevents any inter twinning to happen from other
   * client users.
   *
   * @param commands list of command to send
   * @return a future with the result of the operation
   */
  default Future<List<@Nullable Response>> batch(List<Request> commands) {
    final Promise<List<@Nullable Response>> promise = Promise.promise();
    batch(commands, promise);
    return promise.future();
  }

  /**
   * Returns the address associated with this client.
   * @return the address.
   */
  String socketAddress();

  void close();
}
