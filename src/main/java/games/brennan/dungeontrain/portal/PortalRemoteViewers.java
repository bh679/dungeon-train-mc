package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently looking at a portal container through the <i>other</i> copy of it.
 *
 * <p>{@code event.PortalContainerEvents} redirects a click on a corridor's twin-side container to
 * the canonical block entity in the carriage's sub-level plot — see {@link PortalContainerLink}. The
 * two copies are nowhere near each other in coordinates, and vanilla shuts a menu the moment its
 * viewer is out of reach of the block backing it: {@code ServerPlayer.doTick} tests
 * {@code containerMenu.stillValid(this)} every tick, which for a container bottoms out in a distance
 * check against the block entity's own position. A remotely opened menu would slam shut on the very
 * next tick.</p>
 *
 * <p>So this records the cell the player actually <b>clicked</b>, and
 * {@code mixin/ServerPlayerPortalContainerReachMixin} validates against that instead. Reach still
 * applies — walk away from the copy in front of you and the menu closes, exactly as it should — it
 * is simply measured where the player is standing rather than where the items are kept.</p>
 *
 * <p>An entry lives no longer than the menu: it is written after {@code openMenu} returns, and
 * cleared when any container opens or closes for that player, and on logout. A stale entry could
 * only ever hold a menu open one extra tick, since the mixin re-checks that the cell still holds a
 * container.</p>
 */
public final class PortalRemoteViewers {

    /** Player → the corridor cell they clicked. Server thread only in practice; concurrent to be safe. */
    private static final Map<UUID, BlockPos> VIEWED_FROM = new ConcurrentHashMap<>();

    private PortalRemoteViewers() {}

    public static void opened(Player player, BlockPos clicked) {
        VIEWED_FROM.put(player.getUUID(), clicked.immutable());
    }

    public static void closed(Player player) {
        VIEWED_FROM.remove(player.getUUID());
    }

    /** The cell this player's open menu should measure reach against, or {@code null} for vanilla's. */
    @Nullable
    public static BlockPos viewedFrom(Player player) {
        return VIEWED_FROM.isEmpty() ? null : VIEWED_FROM.get(player.getUUID());
    }

    public static void clear() {
        VIEWED_FROM.clear();
    }
}
