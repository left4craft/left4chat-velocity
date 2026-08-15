package me.sisko.left4chat;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * The short server-switch commands (/hub, /survival, ...) that BungeeAliases
 * provided on the old proxy. Velocity has no equivalent plugin, so they live
 * here instead of pulling in a third-party dependency for six commands.
 *
 * <p>Commands are registered unconditionally and permission-gated inside
 * execute(), matching BungeeAliases: a gated command answers with the
 * configured message rather than pretending not to exist.
 */
final class ServerAliasCommands {

  private final Object plugin;
  private final ProxyServer proxy;
  private final List<Left4ChatConfig.Alias> aliases;
  private final Left4ChatConfig.Messages messages;
  private final Logger logger;

  ServerAliasCommands(Object plugin, ProxyServer proxy, List<Left4ChatConfig.Alias> aliases,
      Left4ChatConfig.Messages messages, Logger logger) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.aliases = aliases;
    this.messages = messages;
    this.logger = logger;
  }

  void register() {
    CommandManager commands = proxy.getCommandManager();
    for (Left4ChatConfig.Alias alias : aliases) {
      CommandMeta meta = commands.metaBuilder(alias.command()).plugin(plugin).build();
      commands.register(meta, command(alias));
    }
    logger.info("Registered {} server alias commands", aliases.size());
  }

  private SimpleCommand command(Left4ChatConfig.Alias alias) {
    return invocation -> {
      if (!(invocation.source() instanceof Player player)) {
        invocation.source().sendMessage(messages.playersOnly());
        return;
      }
      if (alias.permission() != null && !player.hasPermission(alias.permission())) {
        player.sendMessage(messages.aliasNoPermission(alias.server()));
        return;
      }
      Optional<RegisteredServer> target = proxy.getServer(alias.server());
      if (target.isEmpty()) {
        // A name mismatch with velocity.toml, not a player mistake.
        logger.warn("Alias /{} points at unknown server '{}'", alias.command(), alias.server());
        player.sendMessage(messages.aliasUnknownServer(alias.server()));
        return;
      }
      boolean alreadyThere = player.getCurrentServer()
          .map(current -> current.getServerInfo().getName().equalsIgnoreCase(alias.server()))
          .orElse(false);
      if (alreadyThere) {
        player.sendMessage(messages.aliasAlreadyConnected(alias.server()));
        return;
      }
      player.sendMessage(messages.aliasConnecting(alias.server()));
      player.createConnectionRequest(target.get()).fireAndForget();
    };
  }
}
