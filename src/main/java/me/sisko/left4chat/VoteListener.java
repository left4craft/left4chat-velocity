package me.sisko.left4chat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.ProxyServer;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.velocity.event.VotifierEvent;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Turns NuVotifier votes into rewards.
 *
 * <p>NuVotifier (running on this proxy) receives the vote from the voting site
 * and fires {@link VotifierEvent}. This announces it network-wide and publishes
 * the configured console commands to each server's Redis console relay channel,
 * where the Paper-side Left4Chat executes them.
 *
 * <p>Registered only when NuVotifier is actually present -- the event class
 * comes from its jar, so registering without it would fail to load this class.
 */
final class VoteListener {

  /**
   * The vote payload is remote input and the username lands inside a console
   * command, so anything that is not a plain Mojang username is dropped rather
   * than relayed.
   */
  private static final Pattern VALID_USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

  private final Left4Chat plugin;
  private final ProxyServer proxy;
  private final RedisBridge redis;
  private final Left4ChatConfig.Votes config;
  private final Logger logger;

  VoteListener(Left4Chat plugin, ProxyServer proxy, RedisBridge redis,
      Left4ChatConfig.Votes config, Logger logger) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.redis = redis;
    this.config = config;
    this.logger = logger;
  }

  @Subscribe
  public void onVote(VotifierEvent event) {
    Vote vote = event.getVote();
    String player = vote.getUsername();
    String service = vote.getServiceName();

    if (player == null || !VALID_USERNAME.matcher(player).matches()) {
      logger.warn("Ignoring vote with invalid username {} from {}", player, service);
      return;
    }

    logger.info("{} voted on {}", player, service);

    // The Redis publishes are socket writes; keep them off the event thread,
    // same as the join/leave handlers.
    proxy.getScheduler().buildTask(plugin, () -> {
      proxy.sendMessage(config.broadcast(player, service));
      for (Map.Entry<String, List<String>> entry : config.commands().entrySet()) {
        String channel = config.consoleChannel(entry.getKey());
        for (String command : entry.getValue()) {
          redis.publish(channel, Left4ChatConfig.Votes.substitute(command, player, service));
        }
      }
    }).schedule();
  }
}
