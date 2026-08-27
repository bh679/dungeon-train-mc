package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Dungeon Train is balanced around dying costing you something. A world running with the vanilla
 * {@code keepInventory} game rule on is not playing that game — the train can be ridden to the end
 * with no loss — so the whole world runs in <b>Free Play</b> (see {@link RunIntegrity}): stats and
 * advancements stop persisting to the cross-world profile.
 *
 * <p>DT deliberately keeps <em>supporting</em> the rule: {@code DeathScreenLayoutHandler} still
 * snapshots the player's inventory + XP and carries both (and the rule itself) into the next world,
 * restored by {@code KeepInventoryCarryEvents}. Nothing about how the game plays changes here. Only
 * the persistence gate does.</p>
 *
 * <p>This is the sixth source of the Free Play taint, and the second — after
 * {@link PortalTuningIntegrity} — that is <b>per-world and permanent, not per-session and
 * derived</b>. Turning the rule back off does not clear it: the gear carried through a death is
 * already in the save, exactly as the track laid at a retuned portal rate is. The flag lives on
 * {@link DungeonTrainWorldData} and is written once, never cleared.</p>
 *
 * <p><b>Why not leave this to the command allowlist.</b> {@code /gamerule keepInventory true} taints
 * the player who typed it ({@link CommandAllowlist} — nothing is allowlisted, so every
 * {@code /gamerule} does), but a world <em>created</em> with Keep Inventory ticked on in the
 * world-creation screen runs no command at all, and DT then carries that rule into every world after
 * it. Keying off the rule's live value instead of the command closes that, and covers every other
 * route the rule can arrive by (another mod, a datapack, an operator on a dedicated server).</p>
 *
 * <p>Like {@link PortalTuningIntegrity} the flag is mirrored into a static so the ~20 persistence
 * gates that call {@link RunIntegrity#isCheated} never touch SavedData on a hot path, and the live
 * rule read happens on the server thread in {@link #onServerTick} rather than inside the getter.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class KeepInventoryIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mirror of the loaded world's flag. False until an overworld is loaded, and after it unloads. */
    private static volatile boolean worldKept = false;

    private KeepInventoryIntegrity() {}

    /** True when this world has run with {@code keepInventory} on, so the whole world is Free Play. */
    public static boolean isWorldFreePlay() {
        return worldKept;
    }

    /**
     * Mirror the saved flag at overworld load, before anything can ask.
     *
     * <p>{@code HIGH} and matched to {@link PortalTuningIntegrity#onOverworldLoad}: {@link
     * LevelEvent.Load} for the overworld fires inside {@code MinecraftServer.createLevels}, ahead of
     * the spawn region generating, so the answer is right for the first carriage stamped.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;
        worldKept = DungeonTrainWorldData.get(overworld).isKeepInventoryUsed();
        if (worldKept) {
            LOGGER.info("[DungeonTrain] This world has run with keepInventory on — Free Play.");
        }
    }

    /**
     * Watch the live rule until it trips once.
     *
     * <p>A tick hook rather than a read inside {@link #isWorldFreePlay} keeps the gate itself free of
     * side effects and off the game-rule map, and keeps the SavedData write on the server thread.
     * Once latched this early-returns on the first line, so the steady-state cost is one volatile
     * read per tick; before that it is one boolean lookup.</p>
     *
     * <p>Catches every route the rule can turn on within a tick — the world-creation screen, a
     * {@code /gamerule} the player confirmed through the Free Play prompt, another mod, a
     * datapack.</p>
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (worldKept) return;
        MinecraftServer server = event.getServer();
        if (!server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        markKept(server);
    }

    /**
     * Record that this world has run with the rule on, and switch it to Free Play from here on.
     *
     * <p>Writes through to the save immediately rather than only to the static, so a crash before the
     * next autosave cannot lose the taint. (A world that lost it anyway would simply re-latch on the
     * next load, as long as the rule is still on — which is precisely the case the write-through is
     * covering.)</p>
     */
    private static void markKept(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) return; // pre-createLevels tick; the next one will catch it
        DungeonTrainWorldData.get(overworld).markKeepInventoryUsed();
        worldKept = true;
        LOGGER.info("[DungeonTrain] keepInventory is on — this world is now Free Play.");
        announce(server);
    }

    /**
     * Tell everyone online, once, at the moment it trips — the badge appearing with no explanation is
     * how a Free Play cause reads as a bug.
     *
     * <p>Players who are <em>already</em> permanently cheated are skipped: that is the player who
     * just ran {@code /gamerule keepInventory true} and was told by {@link RunIntegrity#markCheated}
     * a tick ago. Everyone who joins later is told by {@code CheatDetectionEvents.onLogin}.</p>
     */
    private static void announce(MinecraftServer server) {
        Component cause = Component.translatable("chat.dungeontrain.free_play.cause.keep_inventory");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RunIntegrity.applyFreePlayEffect(player);
            if (RunIntegrity.isPermanentlyCheated(player)) continue;
            RunIntegrity.sendFreePlayNotice(player, cause);
        }
    }

    /** A second world in the same game session must not inherit the first world's answer. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        worldKept = false;
    }
}
