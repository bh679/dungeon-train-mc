package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Dungeon Train is balanced against the rate portals arrive at. A world where someone has retuned
 * that rate ({@code /dungeontrain portal carriage …}) is not playing the game DT balanced, so the
 * whole world runs in <b>Free Play</b> (see {@link RunIntegrity}): stats and advancements stop
 * persisting to the cross-world profile.
 *
 * <p>This is the fifth source of the Free Play taint, alongside {@link AisDataIntegrity} (modified
 * AIS config), {@link DtConfigIntegrity} (modified DT balance config), {@link CheatModIntegrity}
 * (known cheat mods) and {@link EditorContentIntegrity} (custom Train Editor content). It differs
 * from all four in one way that matters:</p>
 *
 * <p><b>It is per-world and permanent, not per-session and derived.</b> The other four ask "is this
 * true right now" and stop tainting the moment the cause is removed. A retuned rate cannot be
 * taken back — the track that generated at it is already in the save — so this one is a flag on
 * {@link DungeonTrainWorldData} that is written once and never cleared. Setting the rate back to its
 * default does not clear it.</p>
 *
 * <p><b>Why the world and not the player.</b> {@code RUN_CHEATED} is a per-player attachment, so the
 * ordinary command path ({@code CommandAllowlist} → {@code CheatDetectionEvents}) taints only
 * whoever typed the command. That is the right shape for {@code /give}, which benefits one player,
 * and the wrong shape for this: the rate changes the track <em>everybody</em> on the server rides.
 * Keying off the world instead means every player inherits it — including anyone who joins later,
 * since {@link #isWorldFreePlay()} is read per call rather than stamped onto players at join.</p>
 *
 * <p>The flag is mirrored into a static at overworld load so the ~20 persistence gates that call
 * {@link RunIntegrity#isCheated} never touch SavedData on a hot path — the same shape
 * {@link EditorContentIntegrity} uses for the world's custom-content choice.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalTuningIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mirror of the loaded world's flag. False until an overworld is loaded, and after it unloads. */
    private static volatile boolean worldTuned = false;

    private PortalTuningIntegrity() {}

    /** True when the loaded world's portal rate has been retuned, so the whole world is Free Play. */
    public static boolean isWorldFreePlay() {
        return worldTuned;
    }

    /**
     * Record that this world's portal rate has been retuned, and switch it to Free Play from here on.
     *
     * <p>Writes through to the save immediately rather than only to the static, so a crash between
     * the command and the next autosave cannot lose the taint while keeping the retuned rate.</p>
     */
    public static void markTuned(ServerLevel level) {
        if (worldTuned) return;
        DungeonTrainWorldData.get(level.getServer().overworld()).markPortalRateTuned();
        worldTuned = true;
        LOGGER.info("[DungeonTrain] Portal rate retuned — this world is now Free Play.");
    }

    /**
     * Mirror the saved flag at overworld load, before anything can ask.
     *
     * <p>{@code HIGH} and matched to {@link EditorContentIntegrity#onOverworldLoad}: {@link
     * LevelEvent.Load} for the overworld fires inside {@code MinecraftServer.createLevels}, ahead of
     * the spawn region generating, so the answer is right for the first carriage stamped.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;
        worldTuned = DungeonTrainWorldData.get(overworld).isPortalRateTuned();
        if (worldTuned) {
            LOGGER.info("[DungeonTrain] This world's portal rate was retuned — Free Play.");
        }
    }

    /** A second world in the same game session must not inherit the first world's answer. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        worldTuned = false;
    }
}
