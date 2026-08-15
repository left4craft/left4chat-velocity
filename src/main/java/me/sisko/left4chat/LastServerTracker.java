package me.sisko.left4chat;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Reconnects players to the server they were last on, replacing the old
 * BungeeCord proxy's {@code reconnect_yaml} module. Velocity has no built-in
 * equivalent.
 *
 * <p>The last-known server is written to a Redis hash on every backend
 * connection (not on disconnect, so a proxy or backend crash cannot lose it),
 * and read back when the player logs in. If nothing is stored -- or the stored
 * server is excluded, unregistered, or Redis is down -- the login falls
 * through to Velocity's normal {@code try} order, i.e. the hub.
 */
final class LastServerTracker {

  private final Object plugin;
  private final ProxyServer proxy;
  private final RedisBridge redis;
  private final Left4ChatConfig.LastServer config;
  private final Logger logger;

  LastServerTracker(Object plugin, ProxyServer proxy, RedisBridge redis,
      Left4ChatConfig.LastServer config, Logger logger) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.redis = redis;
    this.config = config;
    this.logger = logger;
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    String server = event.getServer().getServerInfo().getName();
    if (isExcluded(server)) {
      return;
    }
    String uuid = event.getPlayer().getUniqueId().toString();
    // Off the event thread: this is network I/O and nothing waits on it.
    proxy.getScheduler()
        .buildTask(plugin, () -> redis.hashSet(config.key(), uuid, server))
        .schedule();
  }

  @Subscribe
  public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    // Login waits for this handler, so the Redis read runs as an async
    // continuation rather than blocking a netty thread.
    return EventTask.async(() -> {
      String stored = redis.hashGet(config.key(), event.getPlayer().getUniqueId().toString());
      if (stored == null || isExcluded(stored)) {
        return;
      }
      Optional<RegisteredServer> server = proxy.getServer(stored);
      if (server.isEmpty()) {
        logger.warn("Not reconnecting {} to unknown server '{}'",
            event.getPlayer().getUsername(), stored);
        return;
      }
      event.setInitialServer(server.get());
    });
  }

  private boolean isExcluded(String server) {
    return config.excludedServers().stream().anyMatch(server::equalsIgnoreCase);
  }
}
