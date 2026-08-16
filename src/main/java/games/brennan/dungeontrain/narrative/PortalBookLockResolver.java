package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.event.PoliticalFilterMirror;
import games.brennan.dungeontrain.event.SharedBookReadMirror;
import games.brennan.dungeontrain.event.PortalCarriageEvents;
import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import games.brennan.dungeontrain.player.PlayerRunState;
import games.brennan.dungeontrain.portal.PortalRoomAuthorLocks;
import games.brennan.dungeontrain.portal.PortalRoomBooks;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Turns "this book came out of a locked room" into "this is the book".
 *
 * <p>Sits between {@link PortalBookLockTag} (the marker the room stamped) and
 * {@link PortalRoomAuthorLocks} (which author that room settled on), and hands the resulting
 * catalogue to the ordinary {@link SharedBookSelector}. Reusing the selector rather than picking at
 * random is the point: it carries the Kid-mode and political hard filters, which are the two things a
 * shortcut here would quietly drop.</p>
 *
 * <h2>Which room</h2>
 * <p>The room is resolved from where the PLAYER is standing, not from anything stored on the stack.
 * A pending book resolves the moment it reaches a hand or the inventory sweep catches it, which is
 * inside the room it came out of. Carry an unresolved one out and there is no room to ask, so it
 * resolves as an ordinary community book — which is the honest answer, since it is no longer a book
 * from that library.</p>
 */
public final class PortalBookLockResolver {

    private PortalBookLockResolver() {}

    /**
     * The book this locked stack should become, or empty when the lock does not apply or cannot be
     * resolved yet.
     *
     * <p>Empty is not a failure the caller has to handle specially — it means "resolve this one the
     * ordinary way", which is exactly what a room with no reachable author should look like.</p>
     */
    public static Optional<SharedBookPool.PoolBook> select(ServerPlayer player, ItemStack stack,
                                                           PlayerRunState run, long seed) {
        PortalRoomBooks mode = PortalBookLockTag.of(stack);
        if (!mode.locks()) return Optional.empty();

        Integer pairKey = PortalCarriageEvents.portalRoomBodyPairKey(
            CarriageDims.DEFAULT, player.getX(), player.getY(), player.getZ());
        if (pairKey == null) return Optional.empty();     // carried out of the room — no library to draw on

        boolean kidSafe = ContentModeMirror.isKid(player);
        Optional<BookAuthorsClient.Author> author =
            PortalRoomAuthorLocks.authorFor(player, pairKey, mode, kidSafe);
        if (author.isEmpty()) return Optional.empty();    // still fetching, or nobody could be resolved

        List<SharedBookPool.PoolBook> catalogue = AuthorBookPool.booksFor(author.get().token());
        if (catalogue.isEmpty()) return Optional.empty();

        SharedBookSelector.PlayerContext ctx = new SharedBookSelector.PlayerContext(
            WorldInfoReporter.clientLanguage(player),
            id -> SharedBookReadMirror.has(player, id),
            run::wasServed,
            id -> { Integer c = run.servedCarriage(id); return c == null ? 0 : c; },
            run.travelledCarriageIndex(),
            DungeonTrainConfig.getSharedBookRepeatCarriages(),
            kidSafe,
            PoliticalFilterMirror.isEnabled(player));
        return SharedBookSelector.select(catalogue, ctx, seed);
    }
}
