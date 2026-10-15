package games.brennan.dungeontrain.discord.chat;

import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Wire form of a reaction list plus its optional event, shared by the reaction packet. */
public final class ChatReactionCodec {

    public static final int MAX_EMOJI = 20;

    private ChatReactionCodec() {}

    public static void write(FriendlyByteBuf buf, List<ChatReaction> list, @Nullable ChatReactionEvent event) {
        int n = Math.min(list.size(), MAX_EMOJI);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) list.get(i).write(buf);
        buf.writeBoolean(event != null);
        if (event != null) event.write(buf);
    }

    public static List<ChatReaction> readList(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_EMOJI);
        List<ChatReaction> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(ChatReaction.read(buf));
        return List.copyOf(out);
    }

    @Nullable
    public static ChatReactionEvent readEvent(FriendlyByteBuf buf) {
        return buf.readBoolean() ? ChatReactionEvent.read(buf) : null;
    }
}
