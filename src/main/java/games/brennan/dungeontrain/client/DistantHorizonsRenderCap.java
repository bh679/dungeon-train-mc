package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.worldgen.DhHorizon;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.OptionalLong;

/**
 * Lowers <b>Distant Horizons</b>' render distance where its full horizon would give away what lies
 * ahead: past the far side of a void, or more than one legacy era away. {@link DhHorizon} decides
 * how far is allowed; this class only applies it.
 *
 * <p><b>The player's setting is the ceiling.</b> The cap goes through DH's API override
 * ({@link IDhApiConfigValue#setValue}), which DH keeps apart from the value in its config file
 * ({@link IDhApiConfigValue#getTrueValue}) and never saves. The override is always
 * {@code min(cap, trueValue)}, read fresh every tick so a change made in DH's own menu is honoured,
 * and wherever the view is clear the override is dropped ({@link IDhApiConfigValue#clearValue}) rather
 * than set — DH is back on exactly the player's setting, never above it. Nothing persists, so a crash
 * mid-cap cannot leave DH shortened.</p>
 *
 * <p><b>Reload cost.</b> A new DH render distance rebuilds DH's LOD tree — visibly — so which
 * distance to apply is left to {@link DhCapPolicy}: a few coarse tiers, lowered at once but raised only
 * with headroom, and held still while DH is hidden. Checked per client tick, not per frame.</p>
 *
 * <p><b>Loading.</b> Like {@link DistantHorizonsSuppression}, this names DH types and is reached only
 * behind the {@code ModList} check in {@link DungeonTrainClient}. Any failure disables the cap and
 * leaves DH on the player's setting.</p>
 */
public final class DistantHorizonsRenderCap {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The override currently applied, or {@link DhCapPolicy#RELEASED} when DH is on the player's own setting. */
    private static int applied = DhCapPolicy.RELEASED;
    private static boolean failed = false;
    /** The cap is below DH's minimum render distance — no radius DH accepts is safe, so it must not draw. */
    private static volatile boolean belowFloor = false;

    private DistantHorizonsRenderCap() {}

    /**
     * Whether the allowed radius is smaller than DH's own minimum render distance (32 chunks), so even
     * the shortest distance DH accepts would draw past a void. {@link DistantHorizonsSuppression} skips
     * DH's frame while this holds — deep in or right at the edge of a void, where there is nothing for
     * DH to show anyway.
     */
    static boolean belowFloor() {
        return belowFloor;
    }

    /** Bind the per-tick update and the logout reset. Call once, on the client, only when DH is loaded. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> release());
        LOGGER.info("[DungeonTrain] Distant Horizons' render distance will be capped at voids and legacy eras");
    }

    private static void tick() {
        if (failed) return;
        try {
            IDhApiConfigValue<Integer> distance = renderDistance();
            if (distance == null) return;
            OptionalLong cap = capBlocksHere();
            Integer minValue = distance.getMinValue();
            int min = minValue == null ? 1 : minValue;
            long capChunks = cap.isPresent() ? cap.getAsLong() / 16L : Long.MAX_VALUE;
            belowFloor = capChunks < min;
            int target = DhCapPolicy.next(applied, capChunks, distance.getTrueValue(), min);
            if (target == DhCapPolicy.RELEASED) {
                release(distance);
            } else if (target != applied && distance.setValue(target)) {
                applied = target;
            }
        } catch (Throwable t) {
            failed = true;
            belowFloor = false;
            LOGGER.warn("[DungeonTrain] Distant Horizons render cap disabled after an error; "
                    + "DH stays on your own render distance: {}", t.toString());
            release();
        }
    }

    /** The allowed radius at the camera, or empty when this world or position has nothing to hide. */
    private static OptionalLong capBlocksHere() {
        if (!ClientDisplayConfig.isLoaded() || !ClientVoidBand.startsWithTrain()) return OptionalLong.empty();
        boolean voids = ClientDisplayConfig.DISTANT_HORIZONS_LIMIT_PAST_VOIDS.get();
        boolean legacy = ClientDisplayConfig.DISTANT_HORIZONS_LIMIT_LEGACY_ERAS.get();
        if (!voids && !legacy) return OptionalLong.empty();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return OptionalLong.empty();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return OptionalLong.empty();
        return DhHorizon.capBlocks(WorldGenCycle.fromConfig(), camera.getPosition().x, voids, legacy);
    }

    private static IDhApiConfigValue<Integer> renderDistance() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        return configs == null ? null : configs.graphics().chunkRenderDistance();
    }

    /** Drop the override so DH is back on the player's own setting. */
    private static void release() {
        try {
            IDhApiConfigValue<Integer> distance = renderDistance();
            if (distance != null) release(distance);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not clear the Distant Horizons render cap: {}", t.toString());
        }
    }

    private static void release(IDhApiConfigValue<Integer> distance) {
        if (applied == DhCapPolicy.RELEASED) return;
        distance.clearValue();
        applied = DhCapPolicy.RELEASED;
    }
}
