package me.sisko.left4chat;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import org.slf4j.Logger;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

/**
 * Owns the connection to Redis.
 *
 * <p>The original opened a brand new socket and re-authenticated on every single
 * join, leave and player-list refresh, then closed it again. {@link RedisClient}
 * pools connections internally instead, and Redis being unreachable is treated
 * as non-fatal -- the network should keep working when the Discord bridge does
 * not.
 */
final class RedisBridge implements AutoCloseable {

  private final Logger logger;
  private final RedisClient client;
  private final String chatChannel;
  private final String playerListKey;

  RedisBridge(Left4ChatConfig.Redis config, Logger logger) {
    this.logger = logger;
    this.chatChannel = config.chatChannel();
    this.playerListKey = config.playerListKey();

    DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis((int) config.timeout().toMillis())
        .socketTimeoutMillis((int) config.timeout().toMillis())
        .database(config.database())
        .clientName("left4chat");
    if (!config.password().isEmpty()) {
      clientConfig.password(config.password());
    }
    if (!config.username().isEmpty()) {
      clientConfig.user(config.username());
    }
    JedisClientConfig built = clientConfig.build();

    ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
    poolConfig.setMaxTotal(config.maxConnections());
    poolConfig.setMaxIdle(config.maxConnections());
    poolConfig.setMinIdle(1);
    poolConfig.setTestOnBorrow(true);

    this.client = RedisClient.builder()
        .hostAndPort(config.host(), config.port())
        .clientConfig(built)
        .poolConfig(poolConfig)
        .build();
  }

  /** Publishes a {@code {"type": ..., "name": ...}} message on the chat channel. */
  void publishPresence(String type, String playerName) {
    JsonObject json = new JsonObject();
    json.addProperty("type", type);
    json.addProperty("name", playerName);
    publish(chatChannel, json.toString());
  }

  /** Publishes a raw message on an arbitrary channel; best-effort like the rest. */
  void publish(String channel, String message) {
    run("publish to " + channel, redis -> redis.publish(channel, message));
  }

  /** Overwrites the player-list key with the supplied JSON array. */
  void setPlayerList(String json) {
    run("player list update", redis -> redis.set(playerListKey, json));
  }

  /** Writes one field of a hash; best-effort like everything else here. */
  void hashSet(String key, String field, String value) {
    run("hash update of " + key, redis -> redis.hset(key, field, value));
  }

  /**
   * Reads one field of a hash.
   *
   * @return the value, or {@code null} if unset or Redis is unreachable --
   *     callers treat both the same way, as "nothing stored"
   */
  String hashGet(String key, String field) {
    try {
      return client.hget(key, field);
    } catch (Exception e) {
      logger.warn("Redis hash read of {} failed: {}", key, e.toString());
      return null;
    }
  }

  private void run(String what, Consumer<RedisClient> action) {
    try {
      action.accept(client);
    } catch (Exception e) {
      // Announcements and the Discord presence feed are best-effort. Log and
      // carry on rather than letting this surface inside an event handler.
      logger.warn("Redis {} failed: {}", what, e.toString());
    }
  }

  @Override
  public void close() {
    try {
      client.close();
    } catch (Exception e) {
      logger.warn("Error closing the Redis client: {}", e.toString());
    }
  }
}
