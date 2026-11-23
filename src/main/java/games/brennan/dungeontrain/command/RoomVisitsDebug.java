package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.portal.PortalRoomVisits;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug room-visits [reset]} — the caller's visits to dimensional sub-variants
 * whose group carries visit rules, or wipe them. Dev aid for testing ordered sub-variants.
 */
final class RoomVisitsDebug {

    private RoomVisitsDebug() {}

    static int report(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[DungeonTrain] room-visits needs a player"));
            return 0;
        }
        Map<String, Integer> counts = new TreeMap<>(PortalRoomVisits.counts(player));
        if (counts.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[DungeonTrain] No dimensional sub-variant visits recorded."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("[DungeonTrain] Room visits:");
        counts.forEach((room, n) -> sb.append("\n  ").append(room).append(" × ").append(n));
        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return counts.size();
    }

    static int reset(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("[DungeonTrain] room-visits needs a player"));
            return 0;
        }
        PortalRoomVisits.reset(player);
        source.sendSuccess(() -> Component.literal("[DungeonTrain] Room visits cleared."), false);
        return 1;
    }
}
