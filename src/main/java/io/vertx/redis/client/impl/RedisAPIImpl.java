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
package io.vertx.redis.client.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.redis.client.*;

public class RedisAPIImpl implements RedisAPI {

  private final RedisConnection connection;

  public RedisAPIImpl(RedisConnection connection) {
    this.connection = connection;
  }

  @Override
  public Future<Response> send(Command cmd, String... args) {
    final Promise<Response> promise = Promise.promise();
    final Request req = Request.cmd(cmd);

    if (args != null) {
      for (String o : args) {
        if (o == null) {
          req.nullArg();
        } else {
          req.arg(o);
        }
      }
    }

    connection.send(req, promise);
    return promise.future();
  }

  @Override
  public void close() {
    connection.close();
  }
}
