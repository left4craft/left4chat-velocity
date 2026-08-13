package me.sisko.left4chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Keeps the {@code minecraft.players} Redis key in sync with who is online, for
 * the Discord bot to read.
 */
final class PlayerListPublisher {

  private final Left4Chat plugin;
  private final ProxyServer proxy;
  private final RedisBridge redis;
  private final Left4ChatConfig.PlayerList config;
  private final Logger logger;

  /** Guards against N simultaneous joins queueing N identical writes. */
  private final AtomicBoolean publishQueued = new AtomicBoolean();

  PlayerListPublisher(
      Left4Chat plugin,
      ProxyServer proxy,
      RedisBridge redis,
      Left4ChatConfig.PlayerList config,
      Logger logger) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.redis = redis;
    this.config = config;
    this.logger = logger;
  }

  void start() {
    if (config.refreshInterval().isZero() || config.refreshInterval().isNegative()) {
      return;
    }
    proxy.getScheduler()
        .buildTask(plugin, this::publishNow)
        .repeat(config.refreshInterval())
        .delay(config.refreshInterval())
        .schedule();
    logger.info("Publishing the player list every {}s as a safety net",
        config.refreshInterval().toSeconds());
  }

  /**
   * Queues a publish for after the configured delay. The delay exists because
   * the proxy's player collection has not settled yet at the moment a join or
   * disconnect event fires.
   */
  void schedulePublish() {
    if (!publishQueued.compareAndSet(false, true)) {
      return;
    }
    proxy.getScheduler()
        .buildTask(plugin, () -> {
          // Cleared first, so a join landing mid-publish queues a fresh one.
          publishQueued.set(false);
          publishNow();
        })
        .delay(config.publishDelay())
        .schedule();
  }

  void publishNow() {
    JsonArray players = new JsonArray();
    for (Player player : proxy.getAllPlayers()) {
      JsonObject entry = new JsonObject();
      entry.addProperty("username", player.getUsername());
      entry.addProperty("uuid", player.getUniqueId().toString());
      // Bungee's getServer() returned null for a player mid-handshake and the
      // resulting NPE aborted the whole update; an Optional makes that explicit.
      player.getCurrentServer()
          .ifPresentOrElse(
              connection -> entry.addProperty("server", connection.getServerInfo().getName()),
              () -> entry.add("server", com.google.gson.JsonNull.INSTANCE));
      players.add(entry);
    }
    redis.setPlayerList(players.toString());
  }
}
