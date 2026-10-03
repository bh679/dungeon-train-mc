package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Dungeon Train is balanced against its own terrain. A world whose overworld runs anything else —
 * Compatible Terrain, the flat preset, a raised/lowered floor preset, or another mod's world type —
 * runs in <b>Free Play</b> (see {@link RunIntegrity}).
 *
 * <p>Per-world and permanent like {@link PortalTuningIntegrity}, but needs no saved flag: a world's
 * overworld generator is fixed at creation, so it is simply read again at every overworld load.
 * The Nether/End start presets share the default overworld terrain and are not affected.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TerrainIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The noise settings of the default Dungeon Train preset's overworld. */
    static final ResourceLocation DEFAULT_SETTINGS =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "overworld");

    /** Mirror of the loaded world's answer. False until an overworld is loaded, and after it unloads. */
    private static volatile boolean nonDefaultTerrain = false;

    private TerrainIntegrity() {}

    /** True when the loaded world's overworld isn't the default Dungeon Train terrain. */
    public static boolean isWorldFreePlay() {
        return nonDefaultTerrain;
    }

    /** Package-private for unit tests: is this overworld noise-settings id the default DT terrain? */
    static boolean isDefaultTerrain(@Nullable ResourceLocation settingsId) {
        return DEFAULT_SETTINGS.equals(settingsId);
    }

    /** The overworld generator's noise-settings id, or null when it isn't a noise generator. */
    @Nullable
    private static ResourceLocation settingsId(ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
        return noise.generatorSettings().unwrapKey().map(ResourceKey::location).orElse(null);
    }

    /** Same timing as {@link PortalTuningIntegrity#onOverworldLoad}: before the spawn region generates. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;
        ResourceLocation id = settingsId(overworld.getChunkSource().getGenerator());
        nonDefaultTerrain = !isDefaultTerrain(id);
        if (nonDefaultTerrain) {
            LOGGER.info("[DungeonTrain] This world doesn't use the default Dungeon Train terrain"
                + " (overworld settings: {}) — Free Play.", id == null ? "non-noise generator" : id);
        }
    }

    /** A second world in the same game session must not inherit the first world's answer. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        nonDefaultTerrain = false;
    }
}
