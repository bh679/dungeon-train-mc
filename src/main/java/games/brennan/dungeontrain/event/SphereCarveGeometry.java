package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * The track rows a spheres-band carve must leave alone, shared by the inline carve
 * ({@link WorldSpheresEvents}) and the later foreign-sphere fill ({@link WorldSpheresForeignEvents}) so
 * the two agree cell for cell: the bed and rail rows across the track's Z-lane survive, and the train's
 * airspace ({@code airMinZ..airMaxZ} × the rows between bed and tunnel ceiling) stays open through any
 * sphere that crosses the track.
 */
record SphereCarveGeometry(int bedY, int railY, int laneZMin, int laneZMax,
                           int airZMin, int airZMax, int airYMin, int airYMax) {

    static SphereCarveGeometry of(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);
        return new SphereCarveGeometry(g.bedY(), g.railY(), g.trackZMin(), g.trackZMax(),
                tg.airMinZ(), tg.airMaxZ(), g.bedY() + 1, tg.ceilingY() - 1);
    }

    boolean laneZ(int worldZ) {
        return worldZ >= laneZMin && worldZ <= laneZMax;
    }

    boolean airZ(int worldZ) {
        return worldZ >= airZMin && worldZ <= airZMax;
    }

    /** True for a cell no sphere may fill: the track's bed/rail rows, or the train's airspace. */
    boolean reserved(int y, boolean laneZ, boolean airZ) {
        return (laneZ && (y == bedY || y == railY)) || (airZ && y >= airYMin && y <= airYMax);
    }
}
