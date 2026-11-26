package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.farcopies.FarCopiesLayout;
import games.brennan.dungeontrain.client.farcopies.RoomBoxModel;
import games.brennan.dungeontrain.client.farcopies.TileVoxels;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.PortalRoomFarCopiesPacket;
import games.brennan.dungeontrain.portal.PortalRoomTiling;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Client half of drawing an endless room's copies out to the Distant Horizons horizon. DH-free: it
 * decides <i>what</i> to draw (a {@link FarCopiesLayout.Result}), and the DH binding
 * ({@link DistantHorizonsFarCopies}, reached only when DH is installed) hands it to DH and hides DH's
 * terrain while it does.
 *
 * <p><b>The look of a copy is read off a real one.</b> The server stamps copies around the player,
 * and their chunks are loaded here, so a copy's blocks are sampled from the client level once per
 * room — a stamped copy rather than the base room, because under Endless Open a copy is only the
 * floor and the ceiling, and the base room is the one with corridors cut into it. Each block is
 * coloured by its map colour.</p>
 *
 * <p><b>A place, not a state.</b> The packet describes a room; the camera has to be inside the
 * structure ({@link ClientPortalRoomDepth#isInsideStructure}) for anything here to apply, and
 * logging out drops it, so nothing can be left drawn in the next world.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientPortalRoomFarCopies {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Tiles of detail round the centre: one past the stamped window, so its holes are covered. */
    static final int NEAR_RADIUS = PortalRoomTiling.MAX_RADIUS + 2;
    /** Tiles of coarse copies beyond that. */
    static final int MID_RADIUS = 16;
    /** Upper bound on the far ring, whatever DH's distance. */
    static final int MAX_FAR_RADIUS = 1024;
    /** The centre follows the camera only once it is this many tiles off, so walking does not rebuild every step. */
    static final int CENTRE_HYSTERESIS = 2;
    /** Fewest ticks between two layouts while the stamped copies are still changing. */
    static final int REBUILD_INTERVAL_TICKS = 10;
    /** Ticks between samples of the room while its blocks are still arriving. */
    static final int RESAMPLE_TICKS = 20;

    /** A layout ready to draw, numbered so the DH side can tell a new one from the last. */
    public record Snapshot(long version, int roomX, int roomY, int roomZ, FarCopiesLayout.Result layout) {}

    private static volatile PortalRoomFarCopiesPacket packet = PortalRoomFarCopiesPacket.none();
    private static volatile Snapshot snapshot = null;
    private static volatile boolean dhBound = false;
    private static volatile IntSupplier horizonBlocks = () -> 0;
    private static volatile java.util.function.BooleanSupplier drawnRecently = () -> false;
    private static volatile Runnable dhReset = () -> {};

    // Client-thread state.
    private static RoomBoxModel model = null;
    private static Object modelKey = null;
    private static boolean modelSettled = false;
    private static int modelSolid = -1;
    private static long lastSampleTick = Long.MIN_VALUE;
    private static Object layoutKey = null;
    private static int centreX = Integer.MIN_VALUE;
    private static int centreZ = Integer.MIN_VALUE;
    private static long lastBuildTick = Long.MIN_VALUE;
    private static long version = 0;

    private ClientPortalRoomFarCopies() {}

    public static void update(PortalRoomFarCopiesPacket next) {
        packet = next == null ? PortalRoomFarCopiesPacket.none() : next;
    }

    public static void reset() {
        packet = PortalRoomFarCopiesPacket.none();
        snapshot = null;
        model = null;
        modelKey = null;
        modelSettled = false;
        modelSolid = -1;
        lastSampleTick = Long.MIN_VALUE;
        layoutKey = null;
        centreX = Integer.MIN_VALUE;
        centreZ = Integer.MIN_VALUE;
    }

    /** Called once by the DH binding, after it has hooked into DH successfully. */
    public static void bindDistantHorizons(IntSupplier horizonBlocksSupplier,
                                           java.util.function.BooleanSupplier drawnRecentlySupplier,
                                           Runnable resetHook) {
        horizonBlocks = Objects.requireNonNull(horizonBlocksSupplier);
        drawnRecently = Objects.requireNonNull(drawnRecentlySupplier);
        dhReset = Objects.requireNonNull(resetHook);
        dhBound = true;
    }

    /** Whether the feature can run at all this frame: DH is bound and the config allows it. */
    private static boolean enabled() {
        return dhBound && ClientDisplayConfig.isLoaded()
            && ClientDisplayConfig.PORTAL_ROOM_DISTANT_HORIZONS_COPIES.get();
    }

    /**
     * Whether the camera is in an endless room whose copies DH is carrying on. While it is, DH draws
     * our copies and none of its own terrain, instead of being switched off for the frame.
     */
    public static boolean activeAt(double x, double y, double z) {
        return enabled() && !packet.isNone() && ClientPortalRoomDepth.isInsideStructure(x, y, z);
    }

    /** {@link #activeAt} for the camera, with something actually laid out to draw. */
    public static boolean drawingAtCamera() {
        Snapshot s = snapshot;
        if (s == null || s.layout().size() == 0) return false;
        Vec3 pos = cameraPos();
        return pos != null && activeAt(pos.x, pos.y, pos.z);
    }

    /**
     * Whether the room's own fog should stand back to vanilla's distance: the copies go on past it,
     * so fogging them out a few rooms away would hide the very thing being drawn.
     */
    public static boolean liftsRoomFog() {
        // Only on evidence: DH has to have drawn the copies, not merely been handed them. Where it
        // draws none (macOS, or its generic rendering off) the room keeps its fog and looks as it
        // always did.
        return drawingAtCamera() && drawnRecently.getAsBoolean();
    }

    /** The current layout, or {@code null} when there is nothing to draw. */
    public static Snapshot snapshot() {
        return snapshot;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!dhBound) return;
        try {
            tick();
        } catch (RuntimeException e) {
            // Never take the client tick down over a far-render nicety; drop to "nothing drawn".
            LOGGER.warn("[DungeonTrain] Endless room far copies failed; drawing none: {}", e.toString());
            snapshot = null;
            layoutKey = null;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        dhReset.run();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        PortalRoomFarCopiesPacket p = packet;
        Vec3 cam = cameraPos();
        if (level == null || cam == null || !enabled() || p.isNone()
                || !ClientPortalRoomDepth.isInsideStructure(cam.x, cam.y, cam.z)) {
            snapshot = null;
            layoutKey = null;
            return;
        }

        RoomBoxModel m = modelFor(level, p);
        if (m == null || m.isEmpty()) {
            snapshot = null;
            return;
        }

        int camTileX = Math.floorDiv((int) Math.floor(cam.x) - p.roomX(), p.sizeX());
        int camTileZ = Math.floorDiv((int) Math.floor(cam.z) - p.roomZ(), p.sizeZ());
        if (Math.abs(camTileX - centreX) >= CENTRE_HYSTERESIS || Math.abs(camTileZ - centreZ) >= CENTRE_HYSTERESIS
                || centreX == Integer.MIN_VALUE) {
            centreX = camTileX;
            centreZ = camTileZ;
        }

        int farRadius = farRadius(p);
        int maxBoxes = ClientDisplayConfig.PORTAL_ROOM_DISTANT_HORIZONS_MAX_BOXES.get();
        Object key = List.of(modelKey, modelSolid, centreX, centreZ, p.stamped(), p.blocked(), farRadius, maxBoxes);
        if (key.equals(layoutKey)) return;
        long now = level.getGameTime();
        // Throttled only once something is drawn: the first layout of a room goes out at once.
        if (snapshot != null && now - lastBuildTick < REBUILD_INTERVAL_TICKS && now >= lastBuildTick) return;

        FarCopiesLayout.Result result = FarCopiesLayout.build(new FarCopiesLayout.Input(
            m, centreX, centreZ, stampedKeys(p), blockedRects(p),
            NEAR_RADIUS, MID_RADIUS, farRadius, maxBoxes));
        boolean firstForRoom = snapshot == null;
        layoutKey = key;
        lastBuildTick = now;
        if (firstForRoom) {
            LOGGER.info("[DungeonTrain] Endless room far copies: centre {},{} -> {} near + {} outer boxes, far radius {} tiles (DH {} blocks)",
                centreX, centreZ, result.near().size(), result.outer().size(), result.farRadius(), horizonBlocks.getAsInt());
        } else {
            LOGGER.debug("[DungeonTrain] Endless room far copies: centre {},{} -> {} near + {} outer boxes, far radius {}",
                centreX, centreZ, result.near().size(), result.outer().size(), result.farRadius());
        }
        snapshot = new Snapshot(++version, p.roomX(), p.roomY(), p.roomZ(), result);
    }

    /** How many tiles out DH's own render distance reaches, measured on the room's narrower side. */
    private static int farRadius(PortalRoomFarCopiesPacket p) {
        int blocks = Math.max(0, horizonBlocks.getAsInt());
        int pitch = Math.max(1, Math.min(p.sizeX(), p.sizeZ()));
        return Math.max(MID_RADIUS, Math.min(MAX_FAR_RADIUS, blocks / pitch + 1));
    }

    private static Set<Long> stampedKeys(PortalRoomFarCopiesPacket p) {
        Set<Long> keys = new HashSet<>(p.stamped().size() * 2);
        for (PortalRoomFarCopiesPacket.TileRef t : p.stamped()) keys.add(FarCopiesLayout.key(t.x(), t.z()));
        return keys;
    }

    private static List<FarCopiesLayout.TileRect> blockedRects(PortalRoomFarCopiesPacket p) {
        List<FarCopiesLayout.TileRect> rects = new ArrayList<>(p.blocked().size());
        for (PortalRoomFarCopiesPacket.Box b : p.blocked()) {
            FarCopiesLayout.TileRect r = FarCopiesLayout.tilesOf(b.minX(), b.minY(), b.minZ(),
                b.maxX(), b.maxY(), b.maxZ(), p.roomX(), p.roomY(), p.roomZ(), p.sizeX(), p.sizeY(), p.sizeZ());
            if (r != null) rects.add(r);
        }
        return rects;
    }

    // ---------- sampling the room ----------

    /**
     * The room's box model, sampled from a stamped copy whose chunks are loaded.
     *
     * <p><b>Sampled until it settles, not once.</b> A tile is in the server's stamped list the tick
     * it is written, but its blocks reach this client a moment later, and a chunk can be "loaded"
     * here while still all air. So an empty sample is never kept, and the room is sampled again every
     * {@link #RESAMPLE_TICKS} until two samples in a row hold the same number of blocks.</p>
     */
    private static RoomBoxModel modelFor(ClientLevel level, PortalRoomFarCopiesPacket p) {
        Object key = List.of(p.mode(), p.roomX(), p.roomY(), p.roomZ(), p.sizeX(), p.sizeY(), p.sizeZ());
        if (!key.equals(modelKey)) {
            model = null;
            modelKey = key;
            modelSettled = false;
            modelSolid = -1;
            lastSampleTick = Long.MIN_VALUE;
        }
        long now = level.getGameTime();
        if (modelSettled || (lastSampleTick != Long.MIN_VALUE && now - lastSampleTick < RESAMPLE_TICKS)) {
            return model;
        }
        PortalRoomFarCopiesPacket.TileRef sample = sampleTile(level, p);
        if (sample == null) return model;
        lastSampleTick = now;
        TileVoxels voxels = sampleVoxels(level, p, sample);
        int solid = voxels.solidCount();
        if (solid == 0) return model;
        if (solid == modelSolid) {
            modelSettled = true;
            return model;
        }
        modelSolid = solid;
        model = RoomBoxModel.of(voxels);
        LOGGER.info("[DungeonTrain] Endless room far copies: sampled tile {},{} of a {}x{}x{} room ({} blocks) -> {} detail / {} coarse / {} strip boxes",
            sample.x(), sample.z(), p.sizeX(), p.sizeY(), p.sizeZ(), solid,
            model.detail().size(), model.coarse().size(), model.strips().size());
        return model;
    }

    /** The stamped copy nearest the base, other than the base itself, with its chunks loaded. */
    private static PortalRoomFarCopiesPacket.TileRef sampleTile(ClientLevel level, PortalRoomFarCopiesPacket p) {
        PortalRoomFarCopiesPacket.TileRef best = null;
        int bestDist = Integer.MAX_VALUE;
        for (PortalRoomFarCopiesPacket.TileRef t : p.stamped()) {
            if (t.x() == 0 && t.z() == 0) continue;
            int d = Math.max(Math.abs(t.x()), Math.abs(t.z()));
            if (d >= bestDist || !tileLoaded(level, p, t)) continue;
            best = t;
            bestDist = d;
        }
        return best;
    }

    private static boolean tileLoaded(ClientLevel level, PortalRoomFarCopiesPacket p,
                                      PortalRoomFarCopiesPacket.TileRef t) {
        int x0 = p.roomX() + t.x() * p.sizeX();
        int z0 = p.roomZ() + t.z() * p.sizeZ();
        int x1 = x0 + p.sizeX() - 1;
        int z1 = z0 + p.sizeZ() - 1;
        return level.hasChunkAt(new BlockPos(x0, p.roomY(), z0)) && level.hasChunkAt(new BlockPos(x1, p.roomY(), z0))
            && level.hasChunkAt(new BlockPos(x0, p.roomY(), z1)) && level.hasChunkAt(new BlockPos(x1, p.roomY(), z1));
    }

    private static TileVoxels sampleVoxels(ClientLevel level, PortalRoomFarCopiesPacket p,
                                           PortalRoomFarCopiesPacket.TileRef t) {
        TileVoxels.Builder out = new TileVoxels.Builder(p.sizeX(), p.sizeY(), p.sizeZ());
        int x0 = p.roomX() + t.x() * p.sizeX();
        int z0 = p.roomZ() + t.z() * p.sizeZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < p.sizeY(); y++) {
            for (int z = 0; z < p.sizeZ(); z++) {
                for (int x = 0; x < p.sizeX(); x++) {
                    pos.set(x0 + x, p.roomY() + y, z0 + z);
                    int rgb = colourOf(level, pos, level.getBlockState(pos));
                    if (rgb >= 0) out.setRgb(x, y, z, rgb);
                }
            }
        }
        return out.build();
    }

    /** A block's far colour, or {@code -1} when it should not be drawn at all. */
    private static int colourOf(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) return -1;
        MapColor colour = state.getMapColor(level, pos);
        if (colour == null || colour == MapColor.NONE) return -1;
        return colour.col & 0xFFFFFF;
    }

    private static Vec3 cameraPos() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return camera == null ? null : camera.getPosition();
    }
}
