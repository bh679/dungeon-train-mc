package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.UUID;

/**
 * The chunks a visit keeps loaded: the room's own, and the spot the visitor died at — the train's
 * keep-alive measured from there while their body was a dead rider, and the visit holds that spot the
 * same way so the line does not drain into Sable holding while they build.
 */
final class TributeRoomVisitTickets {

    private static final TicketType<UUID> ROOM = TicketType.create("dungeontrain_tribute_room", Comparator.comparing(UUID::toString));
    private static final TicketType<UUID> DEATH_SPOT = TicketType.create("dungeontrain_tribute_room_death_spot", Comparator.comparing(UUID::toString));
    private static final int DEATH_SPOT_RADIUS = 3;

    private TributeRoomVisitTickets() {}

    /** The room's window, so its chunks load (or generate) on the worker threads ahead of the stamp. */
    static void holdRoom(ServerLevel roomLevel, BoundingBox window, UUID player) {
        roomLevel.getChunkSource().addRegionTicket(ROOM, centre(window), radius(window), player);
    }

    static void releaseRoom(ServerLevel roomLevel, BoundingBox window, UUID player) {
        roomLevel.getChunkSource().removeRegionTicket(ROOM, centre(window), radius(window), player);
    }

    static void holdDeathSpot(@Nullable ServerLevel deathLevel, Vec3 deathPos, UUID player) {
        if (deathLevel == null) return;
        deathLevel.getChunkSource().addRegionTicket(DEATH_SPOT, new ChunkPos(BlockPos.containing(deathPos)), DEATH_SPOT_RADIUS, player);
    }

    static void releaseDeathSpot(@Nullable ServerLevel deathLevel, Vec3 deathPos, UUID player) {
        if (deathLevel == null) return;
        deathLevel.getChunkSource().removeRegionTicket(DEATH_SPOT, new ChunkPos(BlockPos.containing(deathPos)), DEATH_SPOT_RADIUS, player);
    }

    /** Whether every chunk {@code window} reaches into, and the ring around it, is loaded and full. Never loads one. */
    static boolean ready(ServerLevel level, BoundingBox window) {
        return loaded(level, window) == waitedOn(window);
    }

    /** How many of the chunks {@link #ready} waits on are loaded and full now. */
    static int loaded(ServerLevel level, BoundingBox window) {
        int n = 0;
        for (int cx = minWaitX(window); cx <= maxWaitX(window); cx++) {
            for (int cz = minWaitZ(window); cz <= maxWaitZ(window); cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) n++;
            }
        }
        return n;
    }

    /** How many chunks {@link #ready} waits on: the window's and one ring. */
    static int waitedOn(BoundingBox window) {
        return (maxWaitX(window) - minWaitX(window) + 1) * (maxWaitZ(window) - minWaitZ(window) + 1);
    }

    static int minWaitX(BoundingBox window) { return (window.minX() >> 4) - 1; }
    static int maxWaitX(BoundingBox window) { return (window.maxX() >> 4) + 1; }
    static int minWaitZ(BoundingBox window) { return (window.minZ() >> 4) - 1; }
    static int maxWaitZ(BoundingBox window) { return (window.maxZ() >> 4) + 1; }

    static ChunkPos centre(BoundingBox window) {
        return new ChunkPos(((window.minX() + window.maxX()) / 2) >> 4, ((window.minZ() + window.maxZ()) / 2) >> 4);
    }

    /**
     * Chunks either side of the centre that cover the whole window and the ring {@link #ready} waits on:
     * every chunk within it is brought to full (the region ticket's level is 33 at its edge).
     */
    static int radius(BoundingBox window) {
        int span = Math.max(window.getXSpan(), window.getZSpan());
        return (span >> 4) / 2 + 2;
    }
}
