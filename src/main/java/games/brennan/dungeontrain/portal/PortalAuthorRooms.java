package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.event.ContentModeMirror;
import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * The server side of {@link PortalAuthorRoomPick}: recognises a pick that landed on an author room,
 * gathers the parent's sub-variants and the cached author pages, and turns the pick's answer into the
 * room and settings a pair stands.
 *
 * <p>An <b>author-room parent</b> is any group parent whose own Books setting stocks
 * ({@link PortalRoomBooks#locks}). Its template is never meant to be stamped — its selfWeight is 0 and
 * it only holds the split — so landing on it, or on any of its members, means "an author room" and the
 * member actually stood is decided here.</p>
 */
public final class PortalAuthorRooms {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Redraws a pair whose author rooms are all gated out may take before it stands the parent. */
    private static final int MAX_REDRAWS = 16;

    /** Offsets a redraw's index past every real pair key — see {@link PortalOwnShelves}. */
    private static final long REDRAW_STRIDE = 1L << 33;

    private static final String PICK_SEPARATOR = "|";

    private PortalAuthorRooms() {}

    /** The room a pair stands and its settings, books already pinned to the share it holds. */
    public record Planned(String roomName, PortalRoomSettings settings) {}

    /**
     * The author-room parent {@code name} belongs to — itself when it is one — or empty when it is
     * not an author room at all.
     */
    public static Optional<String> parentOf(String name) {
        if (name == null) return Optional.empty();
        if (isAuthorParent(name)) return Optional.of(name);
        return TrackVariantGroupStore.findParentOf(TrackKind.PORTAL_ROOM, name)
            .filter(PortalAuthorRooms::isAuthorParent);
    }

    private static boolean isAuthorParent(String name) {
        return TrackVariantGroupStore.exists(TrackKind.PORTAL_ROOM, name)
            && PortalRoomSettings.of(name).books().locks();
    }

    /**
     * The Books setting the own-books lottery should read for {@code name}: the parent's for an author
     * room, since the parent's split is what decides whose books it holds — else the room's own.
     */
    public static PortalRoomBooks lotteryBooks(String name) {
        return PortalRoomSettings.of(parentOf(name).orElse(name)).books();
    }

    /**
     * What {@code pairKey} stands when {@code picked} is an author room, or null when it is not.
     *
     * @param pinnedToSelf the own-books boost chose this pair as a library of the rider's own books
     * @param rider        the player the room is rolled for — whose self page and locale are read;
     *                     null fits without authors
     * @param pickAt       the seeded top-level pick at another index, for a pair whose author rooms
     *                     are every one gated out
     */
    public static Planned plan(ServerLevel level, int pairKey, String picked, boolean pinnedToSelf,
                               GateContext gateCtx, ServerPlayer rider, LongFunction<String> pickAt) {
        Optional<String> parentOpt = parentOf(picked);
        if (parentOpt.isEmpty()) return null;
        String parent = parentOpt.get();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);

        Planned remembered = remembered(data.authorRoomPick(pairKey));
        if (remembered != null) return remembered;

        if (TrackVariantRegistry.eligibleMembers(TrackKind.PORTAL_ROOM, parent, gateCtx).isEmpty()) {
            return redrawn(pairKey, parent, pickAt);
        }
        PortalAuthorRoomPick.Choice choice = decide(level, pairKey, parent, pinnedToSelf, gateCtx, rider);
        if (choice == null) return null;

        PortalRoomAuthorLocks.preLock(pairKey, rider, choice.share(), choice.author());
        data.rememberAuthorRoomPick(pairKey, choice.roomName() + PICK_SEPARATOR + choice.share().id());
        BookAuthorsClient.Author author = choice.author();
        LOGGER.info("[DungeonTrain] Author room pair={} rolled {} → '{}'{}", pairKey, choice.share().id(),
            choice.roomName(), author == null ? "" : " for '" + author.name() + "' (" + author.count() + " book(s))");
        return planned(choice.roomName(), choice.share());
    }

    /**
     * The choice {@link #plan} would commit for {@code pairKey} under {@code parent}, with nothing
     * locked or remembered — also what {@code /dungeontrain debug author-rooms} tallies. Null when
     * every member is gated out. Kicks a fetch of any author page that is cold, as planning does.
     */
    public static PortalAuthorRoomPick.Choice decide(ServerLevel level, int pairKey, String parent,
                                                     boolean pinnedToSelf, GateContext gateCtx,
                                                     ServerPlayer rider) {
        List<TrackVariantGroup.Member> members =
            TrackVariantRegistry.eligibleMembers(TrackKind.PORTAL_ROOM, parent, gateCtx);
        if (members.isEmpty()) return null;
        List<PortalAuthorRoomPick.Candidate> candidates = new ArrayList<>(members.size());
        for (TrackVariantGroup.Member m : members) {
            candidates.add(new PortalAuthorRoomPick.Candidate(
                m.id(), m.weight(), PortalRoomSettings.of(m.id()).books()));
        }
        PortalRoomBooks.Share share = PortalAuthorRoomPick.share(
            PortalRoomSettings.of(parent).books(), pairKey, pinnedToSelf);
        return PortalAuthorRoomPick.choose(
            level.getSeed(), pairKey, share, candidates, authorsFor(rider), PortalRoomStatShelves.FULL_SET);
    }

    /** The cached author pages {@code rider} can offer — both empty with no rider. */
    private static PortalAuthorRoomPick.Authors authorsFor(ServerPlayer rider) {
        boolean kidSafe = rider != null && ContentModeMirror.isKid(rider);
        return new PortalAuthorRoomPick.Authors() {
            @Override
            public List<BookAuthorsClient.Author> self() {
                return PortalRoomAuthorLocks.selfAuthors(rider, kidSafe);
            }

            @Override
            public List<BookAuthorsClient.Author> directory(PortalRoomBooks.Share share, PortalRoomBooks band) {
                return PortalRoomAuthorLocks.directoryAuthors(rider, share, band, kidSafe);
            }
        };
    }

    /** A remembered {@code "<room>|<share>"} that still names a registered room, else null. */
    private static Planned remembered(String pick) {
        if (pick == null) return null;
        int at = pick.lastIndexOf(PICK_SEPARATOR);
        if (at <= 0) return null;
        String room = pick.substring(0, at);
        String shareId = pick.substring(at + 1);
        if (!TrackVariantRegistry.contains(TrackKind.PORTAL_ROOM, room)) return null;
        for (PortalRoomBooks.Share share : PortalRoomBooks.Share.values()) {
            if (share.id().equals(shareId)) return planned(room, share);
        }
        return null;
    }

    /**
     * Every author room is gated out here: draw the lottery again until it lands on something that is
     * not one, so the parent's empty template is never what a player walks into. The parent stands in
     * only if sixteen redraws all come back author rooms.
     */
    private static Planned redrawn(int pairKey, String parent, LongFunction<String> pickAt) {
        for (int attempt = 1; attempt <= MAX_REDRAWS; attempt++) {
            String name = pickAt.apply(pairKey + attempt * REDRAW_STRIDE);
            if (name != null && parentOf(name).isEmpty()) {
                return new Planned(name, PortalRoomSettings.of(name));
            }
        }
        LOGGER.warn("[DungeonTrain] Author room pair={} — every sub-room of '{}' is gated out here and "
            + "no redraw left author rooms; standing the parent", pairKey, parent);
        return new Planned(parent, PortalRoomSettings.of(parent));
    }

    private static Planned planned(String room, PortalRoomBooks.Share share) {
        PortalRoomSettings settings = PortalRoomSettings.of(room);
        return new Planned(room, settings.withBooks(settings.books().only(share)));
    }
}
