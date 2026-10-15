package games.brennan.dungeontrain.discord.chat;

import net.minecraft.network.FriendlyByteBuf;

/**
 * What changed to produce a reaction snapshot: someone ({@code userName}) added or removed an emoji, or
 * a moderator cleared reactions. Only an {@link #isAdd() add} can raise the "reacted to your message" notice.
 */
public record ChatReactionEvent(String action, String name, String id, String userName) {

    public ChatReactionEvent {
        action = ChatReaction.clip(action, 8);
        name = ChatReaction.clip(name, ChatReaction.MAX_NAME);
        id = ChatReaction.clip(id, 32);
        userName = ChatReaction.clip(userName, ChatReaction.MAX_USER);
    }

    public boolean isAdd() {
        return "add".equals(action);
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(action, 8);
        buf.writeUtf(name, ChatReaction.MAX_NAME);
        buf.writeUtf(id, 32);
        buf.writeUtf(userName, ChatReaction.MAX_USER);
    }

    static ChatReactionEvent read(FriendlyByteBuf buf) {
        return new ChatReactionEvent(buf.readUtf(8), buf.readUtf(ChatReaction.MAX_NAME), buf.readUtf(32),
            buf.readUtf(ChatReaction.MAX_USER));
    }
}
