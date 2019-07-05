package io.vertx.redis.client.impl;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.*;
import io.vertx.redis.client.*;
import io.vertx.redis.client.impl.types.ErrorType;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

import static io.vertx.redis.client.Command.ASKING;
import static io.vertx.redis.client.Request.cmd;

public class RedisClusterConnection implements RedisConnection {

  // we need some randomness, it doesn't need
  // to be secure or unpredictable
  private static final Random RANDOM = new Random();

  // number of attempts/redirects when we get connection errors
  // or when we get MOVED/ASK responses
  private static final int RETRIES = 16;

  private static final Map<Command, String> UNSUPPORTEDCOMMANDS = new HashMap<>();
  // reduce from list fo responses to a single response
  private static final Map<Command, Function<List<Response>, Response>> REDUCERS = new HashMap<>();

  public static void addReducer(Command command, Function<List<Response>, Response> fn) {
    REDUCERS.put(command, fn);
  }

  public static void addUnSupportedCommand(Command command, String error) {
    if (error == null || error.isEmpty()) {
      UNSUPPORTEDCOMMANDS.put(command, "RedisClusterClient does not handle command " +
        new String(command.getBytes(), StandardCharsets.ISO_8859_1).split("\r\n")[1] + ", use non cluster client on the right node.");
    } else {
      UNSUPPORTEDCOMMANDS.put(command, error);
    }
  }

  private final Vertx vertx;
  private final RedisOptions options;
  private final Slots slots;
  private final Map<String, RedisConnection> connections;

  RedisClusterConnection(Vertx vertx, RedisOptions options, Slots slots, Map<String, RedisConnection> connections) {
    this.vertx = vertx;
    this.options = options;
    this.slots = slots;
    this.connections = connections;
  }

  @Override
  public RedisConnection exceptionHandler(Handler<Throwable> handler) {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        conn.exceptionHandler(handler);
      }
    }
    return this;
  }

  @Override
  public RedisConnection handler(Handler<Response> handler) {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        conn.handler(handler);
      }
    }
    return this;
  }

  @Override
  public RedisConnection pause() {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        conn.pause();
      }
    }
    return this;
  }

  @Override
  public RedisConnection resume() {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        conn.resume();
      }
    }
    return this;
  }

  @Override
  public RedisConnection fetch(long amount) {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        conn.fetch(amount);
      }
    }
    return this;
  }

  @Override
  public RedisConnection endHandler(@Nullable Handler<Void> handler) {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        conn.endHandler(handler);
      }
    }
    return this;
  }

  @Override
  public RedisConnection send(Request request, Handler<AsyncResult<Response>> handler) {
    // process commands for cluster mode
    final RequestImpl req = (RequestImpl) request;
    final Command cmd = req.command();

    if (UNSUPPORTEDCOMMANDS.containsKey(cmd)) {
      handler.handle(Future.failedFuture(UNSUPPORTEDCOMMANDS.get(cmd)));
      return this;
    }

    if (cmd.isMovable()) {
      // in cluster mode we currently do not handle movable keys commands
      handler.handle(Future.failedFuture("RedisClusterClient does not handle movable keys commands, use non cluster client on the right node."));
      return this;
    }

    if (cmd.isKeyless() && REDUCERS.containsKey(cmd)) {
      final List<Future> responses = new ArrayList<>(slots.size());

      for (int i = 0; i < slots.size(); i++) {

        String[] endpoints = slots.endpointsForSlot(i);

        final Promise<Response> p = Promise.promise();
        send(selectMasterOrSlaveEndpoint(req.command().isReadOnly(), endpoints), RETRIES, req, p);
        responses.add(p.future());
      }
      CompositeFuture.all(responses).setHandler(composite -> {
        if (composite.failed()) {
          // means if one of the operations failed, then we can fail the handler
          handler.handle(Future.failedFuture(composite.cause()));
        } else {
          handler.handle(Future.succeededFuture(REDUCERS.get(cmd).apply(composite.result().list())));
        }
      });

      return this;
    }

    if (cmd.isKeyless()) {
      // it doesn't matter which node to use
      send(selectEndpoint(-1, cmd.isReadOnly()), RETRIES, req, handler);
      return this;
    }

    final List<byte[]> args = req.getArgs();

    if (cmd.isMultiKey()) {
      int currentSlot = -1;

      // args exclude the command which is an arg in the commands response
      int start = cmd.getFirstKey() - 1;
      int end = cmd.getLastKey();
      if (end > 0) {
        end--;
      }
      if (end < 0) {
        end = args.size() + (end + 1);
      }
      int step = cmd.getInterval();

      for (int i = start; i < end; i += step) {
        int slot = ZModem.generate(args.get(i));
        if (currentSlot == -1) {
          currentSlot = slot;
          continue;
        }
        if (currentSlot != slot) {

          if (!REDUCERS.containsKey(cmd)) {
            // we can't continue as we don't know how to reduce this
            handler.handle(Future.failedFuture("No Reducer available for: " + cmd));
            return this;
          }

          final Map<Integer, Request> requests = splitRequest(cmd, args, start, end, step);
          final List<Future> responses = new ArrayList<>(requests.size());

          for (Map.Entry<Integer, Request> kv : requests.entrySet()) {
            final Promise<Response> p = Promise.promise();
            send(selectEndpoint(kv.getKey(), cmd.isReadOnly()), RETRIES, kv.getValue(), p);
            responses.add(p.future());
          }

          CompositeFuture.all(responses).setHandler(composite -> {
            if (composite.failed()) {
              // means if one of the operations failed, then we can fail the handler
              handler.handle(Future.failedFuture(composite.cause()));
            } else {
              handler.handle(Future.succeededFuture(REDUCERS.get(cmd).apply(composite.result().list())));
            }
          });

          return this;
        }
      }

      // all keys are on the same slot!
      send(selectEndpoint(currentSlot, cmd.isReadOnly()), RETRIES, req, handler);
      return this;
    }

    // last option the command is single key
    int start = cmd.getFirstKey() - 1;
    send(selectEndpoint(ZModem.generate(args.get(start)), cmd.isReadOnly()), RETRIES, req, handler);
    return this;
  }

  private Map<Integer, Request> splitRequest(Command cmd, List<byte[]> args, int start, int end, int step) {
    // we will split the request across the slots
    final Map<Integer, Request> map = new IdentityHashMap<>();

    for (int i = start; i < end; i += step) {
      int slot = ZModem.generate(args.get(i));
      // get the client for the slot
      Request request = map.get(slot);
      if (request == null) {
        // we need to create a new one
        request = Request.cmd(cmd);
        // all params before the key get added
        for (int j = 0; j < start; j++) {
          request.arg(args.get(j));
        }
        // add to the map
        map.put(slot, request);
      }
      // request isn't null anymore
      request.arg(args.get(i));
      // all params before the next key get added
      for (int j = i + 1; j < i + step; j++) {
        request.arg(args.get(j));
      }
    }

    // if there are args after the end they must be added to all requests
    final Collection<Request> col = map.values();
    col.forEach(req -> {
      for (int j = end; j < args.size(); j++) {
        req.arg(args.get(j));
      }
    });

    return map;
  }

  private void send(String endpoint, int retries, Request command, Handler<AsyncResult<Response>> handler) {

    final RedisConnection connection = connections.get(endpoint);

    if (connection == null) {
      handler.handle(Future.failedFuture("Missing connection to: " + endpoint));
      return;
    }

    connection.send(command, send -> {
      if (send.failed() && send.cause() instanceof ErrorType && retries >= 0) {
        final ErrorType cause = (ErrorType) send.cause();

        if (cause.is("MOVED")) {
          // cluster is unbalanced, need to reconnect
          handler.handle(Future.failedFuture(cause));
          return;
        }

        if (cause.is("ASK")) {
          connection.send(cmd(ASKING), asking -> {
            if (asking.failed()) {
              handler.handle(Future.failedFuture(asking.cause()));
              return;
            }
            // attempt to recover
            // REQUERY THE NEW ONE (we've got the correct details)
            String addr = cause.slice(' ', 2);

            if (addr == null) {
              // bad message
              handler.handle(Future.failedFuture(cause));
              return;
            }

            // re-run on the new endpoint
            send("redis://" + addr, retries - 1, command, handler);
          });
          return;
        }

        if (cause.is("TRYAGAIN") || cause.is("CLUSTERDOWN")) {
          // TRYAGAIN response or cluster down, retry with backoff up to 1280ms
          long backoff = (long) (Math.pow(2, 16 - Math.max(retries, 9)) * 10);
          vertx.setTimer(backoff, t -> send(endpoint, retries - 1, command, handler));
          return;
        }
      }

      try {
        handler.handle(send);
      } catch (RuntimeException e) {
        e.printStackTrace();
      }

    });
  }

  @Override
  public RedisConnection batch(List<Request> requests, Handler<AsyncResult<List<Response>>> handler) {
    int currentSlot = -1;
    boolean readOnly = false;

    // look up the base slot for the batch
    for (int i = 0; i < requests.size(); i++) {
      // process commands for cluster mode
      final RequestImpl req = (RequestImpl) requests.get(i);
      final Command cmd = req.command();

      if (UNSUPPORTEDCOMMANDS.containsKey(cmd)) {
        handler.handle(Future.failedFuture(UNSUPPORTEDCOMMANDS.get(cmd)));
        return this;
      }

      readOnly |= cmd.isReadOnly();

      // this command can run anywhere
      if (cmd.isKeyless()) {
        continue;
      }

      if (cmd.isMovable()) {
        // in cluster mode we currently do not handle movable keys commands
        handler.handle(Future.failedFuture("RedisClusterClient does not handle movable keys commands, use non cluster client on the right node."));
        return this;
      }

      final List<byte[]> args = req.getArgs();

      if (cmd.isMultiKey()) {
        // args exclude the command which is an arg in the commands response
        int start = cmd.getFirstKey() - 1;
        int end = cmd.getLastKey();
        if (end > 0) {
          end--;
        }
        if (end < 0) {
          end = args.size() + (end + 1);
        }
        int step = cmd.getInterval();

        for (int j = start; j < end; j += step) {
          int slot = ZModem.generate(args.get(j));
          if (currentSlot == -1) {
            currentSlot = slot;
            continue;
          }
          if (currentSlot != slot) {
            // in cluster mode we currently do not handle batching commands which keys are not on the same slot
            handler.handle(Future.failedFuture("RedisClusterClient does not handle batching commands with keys across different slots. TODO: Split the command into slots and then batch."));
            return this;
          }
        }
        // all keys are on the same slot!
        continue;
      }

      // last option the command is single key
      int start = cmd.getFirstKey() - 1;
      if (currentSlot != ZModem.generate(args.get(start))) {
        // in cluster mode we currently do not handle batching commands which keys are not on the same slot
        handler.handle(Future.failedFuture("RedisClusterClient does not handle batching commands with keys across different slots. TODO: Split the command into slots and then batch."));
        return this;
      }
    }

    batch(selectEndpoint(currentSlot, readOnly), RETRIES, requests, handler);
    return this;
  }

  private void batch(String endpoint, int retries, List<Request> commands, Handler<AsyncResult<List<Response>>> handler) {

    final RedisConnection connection = connections.get(endpoint);

    if (connection == null) {
      handler.handle(Future.failedFuture("Missing connection to: " + endpoint));
      return;
    }

    connection.batch(commands, send -> {
      if (send.failed() && send.cause() instanceof ErrorType && retries >= 0) {
        final ErrorType cause = (ErrorType) send.cause();

        if (cause.is("MOVED")) {
          // cluster is unbalanced, need to reconnect
          handler.handle(Future.failedFuture(cause));
          return;
        }

        if (cause.is("ASK")) {
          connection.send(cmd(ASKING), asking -> {
            if (asking.failed()) {
              handler.handle(Future.failedFuture(asking.cause()));
              return;
            }
            // attempt to recover
            // REQUERY THE NEW ONE (we've got the correct details)
            String addr = cause.slice(' ', 2);

            if (addr == null) {
              // bad message
              handler.handle(Future.failedFuture(cause));
              return;
            }

            // re-run on the new endpoint
            batch("redis://" + addr, retries - 1, commands, handler);
          });
          return;
        }

        if (cause.is("TRYAGAIN") || cause.is("CLUSTERDOWN")) {
          // TRYAGAIN response or cluster down, retry with backoff up to 1280ms
          long backoff = (long) (Math.pow(2, 16 - Math.max(retries, 9)) * 10);
          vertx.setTimer(backoff, t -> batch(endpoint, retries - 1, commands, handler));
          return;
        }
      }

      handler.handle(send);
    });
  }

  @Override
  public void close() {
    connections.forEach((key, value) -> {
      if (value != null) {
        value.close();
      }
    });
  }

  @Override
  public boolean pendingQueueFull() {
    for (RedisConnection conn : connections.values()) {
      if (conn != null) {
        if (conn.pendingQueueFull()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Select a Redis client for the given key
   */
  private String selectEndpoint(int keySlot, boolean readOnly) {
    // this command doesn't have keys, return any connection
    // NOTE: this means slaves may be used for no key commands regardless of slave config
    if (keySlot == -1) {
      return slots.randomEndPoint();
    }

    String[] endpoints = slots.endpointsForKey(keySlot);

    // if we haven't got config for this slot, try any connection
    if (endpoints == null || endpoints.length == 0) {
      return options.getEndpoint();
    }
    return selectMasterOrSlaveEndpoint(readOnly, endpoints);
  }

  private String selectMasterOrSlaveEndpoint(boolean readOnly, String[] endpoints) {
    int index = 0;

    // always, never, share
    RedisSlaves useSlaves = options.getUseSlave();

    if (readOnly && useSlaves != RedisSlaves.NEVER && endpoints.length > 1) {
      // always use a slave for read commands
      if (useSlaves == RedisSlaves.ALWAYS) {
        index = RANDOM.nextInt(endpoints.length - 1) + 1;
      }
      // share read commands across master + slaves
      if (useSlaves == RedisSlaves.SHARE) {
        index = RANDOM.nextInt(endpoints.length);
      }
    }
    return endpoints[index];
  }
}
