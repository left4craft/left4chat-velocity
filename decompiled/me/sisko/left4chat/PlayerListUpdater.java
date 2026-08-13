/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  redis.clients.jedis.Jedis
 */
package me.sisko.left4chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import me.sisko.left4chat.Main;
import redis.clients.jedis.Jedis;

public class PlayerListUpdater
implements Runnable {
    private Main proxy;

    public PlayerListUpdater(Main proxy) {
        this.proxy = proxy;
    }

    @Override
    public void run() {
        Collection pList = this.proxy.getProxy().getPlayers();
        JsonArray players = new JsonArray();
        pList.forEach(p -> {
            JsonObject json = new JsonObject();
            json.addProperty("username", p.getDisplayName());
            json.addProperty("uuid", p.getUniqueId().toString());
            json.addProperty("server", p.getServer().getInfo().getName());
            players.add((JsonElement)json);
        });
        Jedis j = new Jedis("10.8.0.2");
        j.auth("REDACTED");
        j.set("minecraft.players", players.toString());
        j.close();
    }
}
