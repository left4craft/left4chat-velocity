/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  litebans.api.Database
 *  net.md_5.bungee.api.ChatColor
 *  net.md_5.bungee.api.chat.ComponentBuilder
 *  net.md_5.bungee.api.event.PlayerDisconnectEvent
 *  net.md_5.bungee.api.event.PostLoginEvent
 *  net.md_5.bungee.api.event.ServerSwitchEvent
 *  net.md_5.bungee.api.plugin.Listener
 *  net.md_5.bungee.api.plugin.Plugin
 *  net.md_5.bungee.event.EventHandler
 *  redis.clients.jedis.Jedis
 */
package me.sisko.left4chat;

import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;
import litebans.api.Database;
import me.sisko.left4chat.MOTDSender;
import me.sisko.left4chat.PlayerListUpdater;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import redis.clients.jedis.Jedis;

public class Main
extends Plugin
implements Listener {
    private String join_prefix = ChatColor.translateAlternateColorCodes((char)'&', (String)"&8[&2+&8] &7");
    private String leave_prefix = ChatColor.translateAlternateColorCodes((char)'&', (String)"&8[&4-&8] &7");
    private String switch_prefix_to = ChatColor.translateAlternateColorCodes((char)'&', (String)"&8[&3<&8] &7");
    private String switch_prefix_from = ChatColor.translateAlternateColorCodes((char)'&', (String)"&8[&3>&8] &7");

    public void onEnable() {
        this.getProxy().getPluginManager().registerListener((Plugin)this, (Listener)this);
    }

    @EventHandler
    public void onJoin(PostLoginEvent e) {
        if (!Database.get().isPlayerBanned(e.getPlayer().getUniqueId(), null)) {
            Jedis jedis = new Jedis("10.8.0.2");
            jedis.auth("REDACTED");
            JsonObject json = new JsonObject();
            json.addProperty("type", "join");
            json.addProperty("name", e.getPlayer().getName());
            jedis.publish("minecraft.chat", json.toString());
            jedis.close();
            this.getProxy().getScheduler().schedule((Plugin)this, (Runnable)new MOTDSender(e.getPlayer()), 1L, TimeUnit.SECONDS);
            this.getProxy().getScheduler().schedule((Plugin)this, (Runnable)new PlayerListUpdater(this), 1L, TimeUnit.SECONDS);
            this.getProxy().broadcast(new ComponentBuilder(String.valueOf(this.join_prefix) + e.getPlayer().getName() + " joined").create());
        }
    }

    @EventHandler
    public void onLeave(PlayerDisconnectEvent e) {
        if (!Database.get().isPlayerBanned(e.getPlayer().getUniqueId(), null)) {
            Jedis jedis = new Jedis("10.8.0.2");
            jedis.auth("REDACTED");
            JsonObject json = new JsonObject();
            json.addProperty("type", "leave");
            json.addProperty("name", e.getPlayer().getName());
            jedis.publish("minecraft.chat", json.toString());
            jedis.close();
            this.getProxy().getScheduler().schedule((Plugin)this, (Runnable)new PlayerListUpdater(this), 1L, TimeUnit.SECONDS);
            this.getProxy().broadcast(new ComponentBuilder(String.valueOf(this.leave_prefix) + e.getPlayer().getName() + " left").create());
        }
    }

    @EventHandler
    public void onSwitch(ServerSwitchEvent e) {
        this.getProxy().getScheduler().schedule((Plugin)this, (Runnable)new PlayerListUpdater(this), 1L, TimeUnit.SECONDS);
        if (e.getFrom() != null) {
            e.getFrom().getPlayers().forEach(p -> {
                if (!p.getUniqueId().equals(e.getPlayer().getUniqueId())) {
                    p.sendMessage(new ComponentBuilder(String.valueOf(this.switch_prefix_to) + e.getPlayer().getName() + " switched to " + e.getPlayer().getServer().getInfo().getName()).create());
                }
            });
            e.getPlayer().getServer().getInfo().getPlayers().forEach(p -> p.sendMessage(new ComponentBuilder(String.valueOf(this.switch_prefix_from) + e.getPlayer().getName() + " switched from " + e.getFrom().getName()).create()));
        }
    }
}
