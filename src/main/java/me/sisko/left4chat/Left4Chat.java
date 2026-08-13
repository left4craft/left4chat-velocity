package me.sisko.left4chat;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.nio.file.Path;
import java.util.UUID;
import litebans.api.Database;
import org.slf4j.Logger;

/**
 * Velocity port of the original BungeeCord Left4Chat proxy plugin.
 *
 * <p>Announces joins, leaves and server switches network-wide, mirrors those
 * events onto Redis for the Discord bot, and keeps a Redis key holding the
 * current player list.
 */
@Plugin(
    id = "left4chat",
    name = "Left4Chat",
    version = Left4Chat.VERSION,
    description = "Network join/leave/switch announcements and the Redis presence bridge",
    authors = {"sisko"},
    dependencies = {@Dependency(id = "litebans")}
)
public final class Left4Chat {

  static final String VERSION = "2.0.0";

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;

  private Left4ChatConfig config;
  private RedisBridge redis;
  private PlayerListPublisher playerList;

  @Inject
  public Left4Chat(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    this.config = Left4ChatConfig.load(dataDirectory, logger);
    this.redis = new RedisBridge(config.redis(), logger);
    this.playerList = new PlayerListPublisher(this, proxy, redis, config.playerList(), logger);
    this.playerList.start();
    logger.info("Left4Chat {} enabled (redis {}:{})",
        VERSION, config.redis().host(), config.redis().port());
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    if (playerList != null) {
      // Players are already gone by now, so this clears the presence key rather
      // than leaving the Discord bot showing a full server forever.
      playerList.publishNow();
    }
    if (redis != null) {
      redis.close();
    }
  }

  @Subscribe
  public void onJoin(PostLoginEvent event) {
    Player player = event.getPlayer();
    // The LiteBans lookup is a SQL round trip and the Redis publish is a socket
    // write. Neither belongs inline in the login sequence -- the original ran
    // both on the Bungee event thread, which was tolerable only because the
    // database was on the LAN.
    proxy.getScheduler().buildTask(this, () -> {
      if (isBanned(player)) {
        return;
      }
      redis.publishPresence("join", player.getUsername());
      proxy.sendMessage(config.messages().join(player.getUsername()));
      playerList.schedulePublish();
    }).schedule();
  }

  @Subscribe
  public void onLeave(DisconnectEvent event) {
    Player player = event.getPlayer();
    proxy.getScheduler().buildTask(this, () -> {
      if (isBanned(player)) {
        return;
      }
      redis.publishPresence("leave", player.getUsername());
      proxy.sendMessage(config.messages().leave(player.getUsername()));
      playerList.schedulePublish();
    }).schedule();
  }

  @Subscribe
  public void onServerConnected(ServerConnectedEvent event) {
    playerList.schedulePublish();

    // Bungee's ServerSwitchEvent#getFrom() was null on the first connection;
    // getPreviousServer() being empty is the exact equivalent.
    RegisteredServer from = event.getPreviousServer().orElse(null);
    if (from == null) {
      return;
    }

    Player player = event.getPlayer();
    RegisteredServer to = event.getServer();

    for (Player other : from.getPlayersConnected()) {
      if (!other.getUniqueId().equals(player.getUniqueId())) {
        other.sendMessage(
            config.messages().switchedTo(player.getUsername(), to.getServerInfo().getName()));
      }
    }
    for (Player other : to.getPlayersConnected()) {
      if (!other.getUniqueId().equals(player.getUniqueId())) {
        other.sendMessage(
            config.messages().switchedFrom(player.getUsername(), from.getServerInfo().getName()));
      }
    }
  }

  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    // Only on the first backend connection, which is where the original's
    // "one second after login" guess was really aiming.
    if (event.getPreviousServer() != null || !config.motd().enabled()) {
      return;
    }
    Player player = event.getPlayer();
    var task = proxy.getScheduler()
        .buildTask(this, () -> player.sendMessage(config.motd().render(player.getUsername())));
    if (!config.motd().delay().isZero()) {
      task.delay(config.motd().delay());
    }
    task.schedule();
  }

  /**
   * Suppresses announcements for players LiteBans is about to kick. Returns
   * {@code false} if LiteBans is unavailable, so a LiteBans outage costs us
   * announcements rather than silencing the network.
   */
  private boolean isBanned(Player player) {
    UUID uuid = player.getUniqueId();
    try {
      return Database.get().isPlayerBanned(uuid, null);
    } catch (Exception e) {
      logger.warn("LiteBans ban lookup failed for {}, announcing anyway", uuid, e);
      return false;
    }
  }
}
