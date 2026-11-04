package games.brennan.dungeontrain.portal.home;

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
final class HomeRoomVisitTickets {

    private static final TicketType<UUID> ROOM = TicketType.create("dungeontrain_home_room", Comparator.comparing(UUID::toString));
    private static final TicketType<UUID> DEATH_SPOT = TicketType.create("dungeontrain_home_room_death_spot", Comparator.comparing(UUID::toString));
    private static final int DEATH_SPOT_RADIUS = 3;

    private HomeRoomVisitTickets() {}

    static void hold(ServerLevel roomLevel, BoundingBox window, @Nullable ServerLevel deathLevel, Vec3 deathPos, UUID player) {
        roomLevel.getChunkSource().addRegionTicket(ROOM, centre(window), radius(window), player);
        if (deathLevel != null) {
            deathLevel.getChunkSource().addRegionTicket(DEATH_SPOT, new ChunkPos(BlockPos.containing(deathPos)), DEATH_SPOT_RADIUS, player);
        }
    }

    static void release(ServerLevel roomLevel, BoundingBox window, @Nullable ServerLevel deathLevel, Vec3 deathPos, UUID player) {
        roomLevel.getChunkSource().removeRegionTicket(ROOM, centre(window), radius(window), player);
        if (deathLevel != null) {
            deathLevel.getChunkSource().removeRegionTicket(DEATH_SPOT, new ChunkPos(BlockPos.containing(deathPos)), DEATH_SPOT_RADIUS, player);
        }
    }

    private static ChunkPos centre(BoundingBox window) {
        return new ChunkPos(((window.minX() + window.maxX()) / 2) >> 4, ((window.minZ() + window.maxZ()) / 2) >> 4);
    }

    /** Chunks either side of the centre that cover the whole window, plus one. */
    private static int radius(BoundingBox window) {
        int span = Math.max(window.getXSpan(), window.getZSpan());
        return (span >> 4) / 2 + 2;
    }
}
