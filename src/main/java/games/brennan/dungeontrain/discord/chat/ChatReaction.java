package games.brennan.dungeontrain.discord.chat;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One emoji's reactions on a Discord message, as shown on its in-game chat line: the emoji
 * ({@code name}, plus {@code id} for a custom server emoji, blank for unicode), how many reacted, and
 * the display names of who did (the relay keeps up to 25; {@code count} stays exact).
 */
public record ChatReaction(String name, String id, boolean animated, int count, List<String> users) {

    static final int MAX_NAME = 64;
    static final int MAX_USERS = 25;
    static final int MAX_USER = 32;

    public ChatReaction {
        name = clip(name, MAX_NAME);
        id = clip(id, 32);
        count = Math.max(0, count);
        users = List.copyOf(users.size() > MAX_USERS ? users.subList(0, MAX_USERS) : users);
    }

    public boolean custom() {
        return !id.isEmpty();
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(name, MAX_NAME);
        buf.writeUtf(id, 32);
        buf.writeBoolean(animated);
        buf.writeVarInt(count);
        buf.writeVarInt(users.size());
        for (String u : users) buf.writeUtf(u, MAX_USER);
    }

    static ChatReaction read(FriendlyByteBuf buf) {
        String name = buf.readUtf(MAX_NAME);
        String id = buf.readUtf(32);
        boolean animated = buf.readBoolean();
        int count = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX_USERS);
        List<String> users = new ArrayList<>(n);
        for (int i = 0; i < n; i++) users.add(buf.readUtf(MAX_USER));
        return new ChatReaction(name, id, animated, count, users);
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
