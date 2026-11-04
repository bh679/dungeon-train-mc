package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.net.DeathStatsPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's last death, as the stats packet the client was sent, kept until the visit that
 * follows saves it beside their room ({@link TributeRoomBlob#report}). A tribute TV on the train replays
 * it as the recap.
 *
 * <p>Stored as the packet's own wire bytes behind a format byte: the packet is what the recap screen
 * runs on, and its codec is the one place its layout lives. A report written under another format
 * (a protocol bump) decodes to nothing, and the TV shows snow rather than a wrong page.</p>
 */
public final class LastDeathReports {

    /** Bumped with {@code DungeonTrainNet.PROTOCOL_VERSION} whenever the packet layout changes. */
    public static final byte FORMAT = 1;

    /** One death: the packet's bytes and the death message as vanilla worded it, third person. */
    public record Report(byte[] bytes, String deathMessage, long atMs) {}

    private static final Map<UUID, Report> LAST = new ConcurrentHashMap<>();

    private LastDeathReports() {}

    public static void remember(ServerPlayer player, DeathStatsPacket packet, String deathMessage) {
        LAST.put(player.getUUID(), new Report(encode(packet, player.registryAccess()), deathMessage, System.currentTimeMillis()));
    }

    @Nullable
    public static Report take(UUID player) {
        return LAST.remove(player);
    }

    @Nullable
    public static Report peek(UUID player) {
        return LAST.get(player);
    }

    public static void forget(UUID player) {
        LAST.remove(player);
    }

    public static void clear() {
        LAST.clear();
    }

    public static byte[] encode(DeathStatsPacket packet, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            buf.writeByte(FORMAT);
            packet.encode(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** The packet in {@code bytes}, or null when they are empty, another format, or unreadable. */
    @Nullable
    public static DeathStatsPacket decode(@Nullable byte[] bytes, RegistryAccess registries) {
        if (bytes == null || bytes.length < 2 || bytes[0] != FORMAT) return null;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries);
        try {
            buf.readByte();
            return DeathStatsPacket.decode(buf);
        } catch (RuntimeException e) {
            return null;
        } finally {
            buf.release();
        }
    }
}
