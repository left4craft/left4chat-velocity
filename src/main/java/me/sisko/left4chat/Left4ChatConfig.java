package me.sisko.left4chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Everything the original plugin hardcoded: the Redis endpoint and credentials,
 * the four announcement prefixes and the MOTD body.
 */
public record Left4ChatConfig(
    Redis redis, PlayerList playerList, Motd motd, Messages messages, List<Alias> aliases) {

  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

  /** A chat command that connects the player to a server, e.g. /hub. */
  public record Alias(String command, String server, String permission) {
  }

  // The old proxy's BungeeAliases config, verbatim.
  private static final List<Alias> DEFAULT_ALIASES = List.of(
      new Alias("hub", "hub", null),
      new Alias("lobby", "hub", null),
      new Alias("survival", "survival", null),
      new Alias("creative", "creative", null),
      new Alias("partygames", "partygames", null),
      new Alias("build", "build", "left4craft.build"));

  public record Redis(
      String host,
      int port,
      String password,
      String username,
      int database,
      Duration timeout,
      int maxConnections,
      String chatChannel,
      String playerListKey) {
  }

  public record PlayerList(Duration publishDelay, Duration refreshInterval) {
  }

  public record Motd(boolean enabled, Duration delay, List<String> lines) {

    public Component render(String playerName) {
      return LEGACY.deserialize(String.join("\n", lines).replace("{player}", playerName));
    }
  }

  public record Messages(
      String join,
      String leave,
      String switchedTo,
      String switchedFrom,
      String aliasConnecting,
      String aliasAlreadyConnected,
      String aliasNoPermission,
      String aliasUnknownServer) {

    public Component join(String player) {
      return format(join, player, null);
    }

    public Component leave(String player) {
      return format(leave, player, null);
    }

    public Component switchedTo(String player, String server) {
      return format(switchedTo, player, server);
    }

    public Component switchedFrom(String player, String server) {
      return format(switchedFrom, player, server);
    }

    public Component aliasConnecting(String server) {
      return format(aliasConnecting, null, server);
    }

    public Component aliasAlreadyConnected(String server) {
      return format(aliasAlreadyConnected, null, server);
    }

    public Component aliasNoPermission(String server) {
      return format(aliasNoPermission, null, server);
    }

    public Component aliasUnknownServer(String server) {
      return format(aliasUnknownServer, null, server);
    }

    public Component playersOnly() {
      return Component.text("Only players can use this command.");
    }

    private static Component format(String template, String player, String server) {
      String out = template;
      if (player != null) {
        out = out.replace("{player}", player);
      }
      if (server != null) {
        out = out.replace("{server}", server);
      }
      return LEGACY.deserialize(out);
    }
  }

  /**
   * Reads {@code plugins/left4chat/config.yml}, writing the bundled defaults out
   * first if it does not exist yet. Any unreadable or malformed file falls back
   * to the defaults rather than aborting startup.
   */
  public static Left4ChatConfig load(Path dataDirectory, Logger logger) {
    Path file = dataDirectory.resolve("config.yml");
    try {
      if (Files.notExists(file)) {
        Files.createDirectories(dataDirectory);
        try (InputStream defaults = Left4ChatConfig.class.getResourceAsStream("/config.yml")) {
          if (defaults == null) {
            throw new IOException("bundled config.yml is missing from the jar");
          }
          Files.copy(defaults, file);
        }
        logger.info("Wrote default configuration to {}", file);
      }
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        Object loaded = new Yaml().load(reader);
        return from(asMap(loaded));
      }
    } catch (Exception e) {
      logger.error("Could not read {}, falling back to built-in defaults", file, e);
      return from(Map.of());
    }
  }

  private static Left4ChatConfig from(Map<String, Object> root) {
    Map<String, Object> redis = section(root, "redis");
    Map<String, Object> playerList = section(root, "player-list");
    Map<String, Object> motd = section(root, "motd");
    Map<String, Object> messages = section(root, "messages");

    return new Left4ChatConfig(
        new Redis(
            string(redis, "host", "127.0.0.1"),
            integer(redis, "port", 6379),
            string(redis, "password", ""),
            string(redis, "username", ""),
            integer(redis, "database", 0),
            Duration.ofMillis(integer(redis, "timeout-millis", 2000)),
            integer(redis, "max-connections", 8),
            string(redis, "chat-channel", "minecraft.chat"),
            string(redis, "player-list-key", "minecraft.players")),
        new PlayerList(
            Duration.ofMillis(integer(playerList, "publish-delay-millis", 1000)),
            Duration.ofSeconds(integer(playerList, "refresh-interval-seconds", 0))),
        new Motd(
            bool(motd, "enabled", true),
            Duration.ofMillis(integer(motd, "delay-millis", 0)),
            strings(motd, "lines", List.of(
                "&3&m-<----------------------------------->-",
                "&6Welcome back, &c{player}&6.",
                "",
                "&6Type &c/help &6to view the help menu.",
                "",
                "&6To switch server, type &c/game&6.",
                "&3&m-<----------------------------------->-"))),
        new Messages(
            string(messages, "join", "&8[&2+&8] &7{player} joined"),
            string(messages, "leave", "&8[&4-&8] &7{player} left"),
            string(messages, "switched-to", "&8[&3<&8] &7{player} switched to {server}"),
            string(messages, "switched-from", "&8[&3>&8] &7{player} switched from {server}"),
            string(messages, "alias-connecting", "&6Switching to &c{server}&6."),
            string(messages, "alias-already-connected", "&6You are already connected to &c{server}&6."),
            string(messages, "alias-no-permission", "&cYou do not have permission to connect to {server}"),
            string(messages, "alias-unknown-server", "&c{server} is not available right now.")),
        aliases(root));
  }

  /**
   * Parses the {@code aliases} section: a mapping of command name to either a
   * plain server name or a {@code {server, permission}} block. A missing or
   * malformed section falls back to the old proxy's BungeeAliases setup.
   */
  private static List<Alias> aliases(Map<String, Object> root) {
    Object value = root.get("aliases");
    if (!(value instanceof Map<?, ?> section) || section.isEmpty()) {
      return DEFAULT_ALIASES;
    }
    List<Alias> out = new ArrayList<>(section.size());
    for (Map.Entry<?, ?> entry : section.entrySet()) {
      String command = String.valueOf(entry.getKey()).toLowerCase().replaceFirst("^/", "");
      if (entry.getValue() instanceof Map<?, ?>) {
        Map<String, Object> block = asMap(entry.getValue());
        String permission = string(block, "permission", "");
        out.add(new Alias(
            command,
            string(block, "server", command),
            permission.isEmpty() ? null : permission));
      } else {
        out.add(new Alias(command, String.valueOf(entry.getValue()), null));
      }
    }
    return List.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  private static Map<String, Object> section(Map<String, Object> parent, String key) {
    return asMap(parent.get(key));
  }

  private static String string(Map<String, Object> map, String key, String fallback) {
    Object value = map.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  private static int integer(Map<String, Object> map, String key, int fallback) {
    Object value = map.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value != null) {
      try {
        return Integer.parseInt(value.toString().trim());
      } catch (NumberFormatException ignored) {
        // fall through to the default
      }
    }
    return fallback;
  }

  private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
    Object value = map.get(key);
    return value instanceof Boolean b ? b : fallback;
  }

  private static List<String> strings(Map<String, Object> map, String key, List<String> fallback) {
    Object value = map.get(key);
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      return fallback;
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object element : list) {
      out.add(element == null ? "" : String.valueOf(element));
    }
    return List.copyOf(out);
  }
}
