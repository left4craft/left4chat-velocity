/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.md_5.bungee.api.ChatColor
 *  net.md_5.bungee.api.chat.ComponentBuilder
 *  net.md_5.bungee.api.connection.ProxiedPlayer
 */
package me.sisko.left4chat;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;

public class MOTDSender
implements Runnable {
    private String motd = "&3&m-<----------------------------------->-\n&6Welcome back, &c{player}&6.\n\n&6Type &c/help &6to view the help menu.\n\n&6To switch server, type &c/game&6.\n&3&m-<----------------------------------->-";
    private ProxiedPlayer p;

    public MOTDSender(ProxiedPlayer p) {
        this.p = p;
        this.motd = this.motd.replace("{player}", p.getName());
    }

    @Override
    public void run() {
        this.p.sendMessage(new ComponentBuilder(ChatColor.translateAlternateColorCodes((char)'&', (String)this.motd)).create());
    }
}
