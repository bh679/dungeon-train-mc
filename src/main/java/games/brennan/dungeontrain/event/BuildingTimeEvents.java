package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalPlayerStats;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.discord.BuilderTimeReporter;
import games.brennan.dungeontrain.editor.EditorLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Time spent AUTHORING content, counted the way {@link BoardingProgressEvents} counts time aboard.
 *
 * <p>Nothing measured this before. Every counter DT keeps is about riding the train, so a player
 * who spends an evening in the Train Builder has, as far as the game is concerned, not played —
 * and the seconds behind the public "Longest Building" board had no source.</p>
 *
 * <p>Two places count as building, and they are counted separately (see
 * {@link GlobalPlayerStats.Building}):</p>
 * <ul>
 *   <li>a <b>Train Builder world</b> — the whole world is the tool, so every tick in it counts;</li>
 *   <li>the <b>sky editor</b> in an ordinary world — the tool is a place you fly up to, so the test
 *       is {@link EditorLayout#isAtPlotHeight}, shared with the overlay's own short-circuit.</li>
 * </ul>
 *
 * <p>Never both: a builder world's plots sit at editor height, and a tick charged twice would make
 * the board a measure of where you stood rather than how long you built.</p>
 *
 * <h2>Cadence</h2>
 * <p>Accrual is a scan every {@link #SCAN_PERIOD_TICKS} that credits the whole period, the same
 * trade {@code BoardingProgressEvents} makes — a per-tick handler for a number nobody reads at
 * per-tick resolution is not worth the tick. The relay hears about it every
 * {@link #REPORT_PERIOD_TICKS} of accrued time and again at logout, so a session that ends in a
 * crash loses at most a few minutes off the board and none off the stat file.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BuildingTimeEvents {

    /** Accrual cadence. One second of ticks, credited in one go. */
    static final int SCAN_PERIOD_TICKS = 20;

    /** Accrued building time between relay reports — five minutes. */
    static final int REPORT_PERIOD_TICKS = 20 * 60 * 5;

    /** Ticks accrued per player since their last relay report. Cleared on report and on logout. */
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();

    private BuildingTimeEvents() {}

    /** Which counter, if any, a player's seconds feed right now. */
    public enum Target { BUILDER, EDITOR, NONE }

    /**
     * The decision, over plain data so it is testable without a level: a builder world claims the
     * time whatever height the player is at, an ordinary world only claims it up at the plots, and
     * everything else — riding, walking, standing on a platform — is not building.
     */
    public static Target targetFor(boolean builderWorld, int blockY) {
        if (builderWorld) return Target.BUILDER;
        return EditorLayout.isAtPlotHeight(blockY) ? Target.EDITOR : Target.NONE;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0L) return;
        var players = level.players();
        if (players.isEmpty()) return;

        boolean builderWorld = isBuilderWorld(level);
        for (ServerPlayer player : players) {
            Target target = targetFor(builderWorld, player.getBlockY());
            if (target == Target.NONE) continue;
            UUID id = player.getUUID();
            if (target == Target.BUILDER) {
                GlobalPlayerStats.addBuilderTicks(id, SCAN_PERIOD_TICKS);
            } else {
                GlobalPlayerStats.addEditorTicks(id, SCAN_PERIOD_TICKS);
            }
            long pending = PENDING.merge(id, (long) SCAN_PERIOD_TICKS, Long::sum);
            if (pending >= REPORT_PERIOD_TICKS) {
                PENDING.remove(id);
                BuilderTimeReporter.report(player, GlobalPlayerStats.buildingTicks(id));
            }
        }
    }

    /**
     * Report whatever hasn't been reported yet on the way out. A builder session usually ends by
     * quitting to the menu rather than by dying, so without this the last stretch of a session
     * would only reach the board on the player's next five-minute mark, in some later session.
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        Long pending = PENDING.remove(id);
        if (pending == null || pending <= 0L) return;
        BuilderTimeReporter.report(player, GlobalPlayerStats.buildingTicks(id));
    }

    /**
     * Whether this level is a Train Builder world — the dimension TYPE, not the key, because a
     * builder world's only dimension is {@code minecraft:overworld} (the same check every
     * {@code builder/} entry point makes). A level that can't resolve its dimension type is not a
     * builder world for our purposes rather than a crash on a tick.
     */
    private static boolean isBuilderWorld(ServerLevel level) {
        try {
            return level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE);
        } catch (Throwable t) {
            return false;
        }
    }
}
