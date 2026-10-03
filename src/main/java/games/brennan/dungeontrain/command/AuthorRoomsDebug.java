package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalAuthorRoomPick;
import games.brennan.dungeontrain.portal.PortalAuthorRooms;
import games.brennan.dungeontrain.portal.PortalRoomBooks;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug author-rooms [count] [player]}: for {@code count} pair keys under this
 * world's seed, how often the room lottery lands on an author room, the share each landing rolls, the
 * sub-room it stands, and whether that room's range holds the author it was fitted to. Ungated, and
 * nothing is locked or remembered — {@link PortalAuthorRooms#decide}. With a player, their self page
 * and the directory are read (run it twice: the first call kicks the fetches). INFO-logged for RCON.
 */
final class AuthorRoomsDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final int DEFAULT_COUNT = 5000;

    private AuthorRoomsDebug() {}

    static int report(CommandSourceStack source, int count, ServerPlayer rider) {
        ServerLevel level = source.getServer().overworld();
        long seed = level.getSeed();
        int landed = 0;
        int fitted = 0;
        int withAuthor = 0;
        Map<String, Integer> shares = new TreeMap<>();
        Map<String, Integer> rooms = new TreeMap<>();
        for (int key = 0; key < count; key++) {
            String picked = TrackVariantRegistry.pickName(TrackKind.PORTAL_ROOM, seed, key, null);
            Optional<String> parent = PortalAuthorRooms.parentOf(picked);
            if (parent.isEmpty()) continue;
            landed++;
            PortalAuthorRoomPick.Choice c = PortalAuthorRooms.decide(level, key, parent.get(), false, null, rider);
            if (c == null) continue;
            shares.merge(c.share().id(), 1, Integer::sum);
            rooms.merge(c.share().id() + "->" + c.roomName(), 1, Integer::sum);
            if (c.author() != null) {
                withAuthor++;
                PortalRoomBooks books = PortalRoomSettings.of(c.roomName()).books();
                if (books.accepts(c.author().count())) fitted++;
            }
        }
        send(source, String.format("[DungeonTrain] author-rooms: %d pairs, seed=%d, rider=%s | landed=%d (%.1f%%)",
            count, seed, rider == null ? "<none>" : rider.getName().getString(), landed,
            100.0 * landed / Math.max(1, count)), ChatFormatting.AQUA);
        send(source, "  shares " + shares, ChatFormatting.GRAY);
        send(source, "  rooms " + rooms, ChatFormatting.GRAY);
        send(source, "  authored up front=" + withAuthor + " room-range-holds-author=" + fitted,
            withAuthor == fitted ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        return landed;
    }

    private static void send(CommandSourceStack source, String line, ChatFormatting colour) {
        LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
    }
}
