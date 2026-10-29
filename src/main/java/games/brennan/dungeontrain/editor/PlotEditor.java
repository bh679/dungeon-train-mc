package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One editor plot lifecycle, written once: layout → enter → stamp → snapshot → save → clear.
 *
 * <p>Every plot editor used to be a static class that spelled this sequence out for its own kind,
 * and the copies drifted (see {@code docs/reviews/train-editor-oop-review.md} §1). A subclass now
 * answers only the per-kind facts — where a model's plot is, how big, how to place and capture it,
 * where it persists — and the sequence itself is {@code final} here. Optional hooks cover the few
 * things kinds genuinely differ on (a doorway to land in, a capture void block, an erase that must
 * run under a stamp guard, sidecars to save alongside the template).</p>
 *
 * <p>Instances are stateless and long-lived; a kind's editor is a singleton the {@link Template}
 * records reach through their own {@code enter} / {@code restampPlot} / {@code editorPlotOrigin}.</p>
 *
 * @param <T> the template record this editor's plots hold
 */
public abstract class PlotEditor<T extends Template> {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- per-kind facts ----------------------------------------------------------------------

    /** The category whose residency gates {@link #plotContaining}. */
    public abstract EditorCategory category();

    /** The {@link EditorPlotSnapshots} key {@link EditorDirtyCheck} compares this plot under. */
    public abstract String snapshotKey(T t);

    /** Origin of {@code t}'s plot, or null when it has none today (an unregistered id). */
    public abstract @Nullable BlockPos origin(T t, CarriageDims dims);

    /** Footprint of {@code t}'s plot: {@code (length, height, width)}. */
    public abstract Vec3i size(T t, CarriageDims dims);

    /** Every model that has a plot right now, in row order. */
    public abstract List<T> resident(CarriageDims dims);

    /** Place {@code t}'s blocks at {@code origin} — from its store, or a fallback when it has none. */
    protected abstract void place(ServerLevel level, BlockPos origin, T t, CarriageDims dims);

    /** Capture the plot at {@code origin} into a fresh template. */
    protected abstract StructureTemplate capture(ServerLevel level, BlockPos origin, T t, CarriageDims dims);

    /**
     * Write {@code template} for {@code t}: the user tier when {@code toSource} is false, the
     * source tree (dev mode) when true. The source write runs through {@link EditorSave#toSource}
     * so it is skipped outside dev mode and reported, never thrown, on failure.
     */
    protected abstract void persist(T t, StructureTemplate template, boolean toSource) throws IOException;

    /** The log noun for {@code t} — {@code "tile/default"}, {@code "room basiccave1"}. */
    protected abstract String describe(T t);

    // ---- optional hooks ----------------------------------------------------------------------

    /** The lower cell of the plot's -X doorway, or null for a plot without one (lands at the centre). */
    protected @Nullable BlockPos door(BlockPos origin, T t, CarriageDims dims) {
        return null;
    }

    /** The block {@link #capture} treats as "not part of this template". */
    protected Block captureVoid() {
        return Blocks.AIR;
    }

    /** Set the footprint and its cage back to air. Override when the erase must run under a guard. */
    protected void erase(ServerLevel level, BlockPos origin, T t, CarriageDims dims) {
        EditorPlotBox.erase(level, origin, size(t, dims), EditorPlotCage.NOTIFY);
    }

    /** Runs before every stamp of {@code t}: drop cached sidecars so the stamp reads disk state. */
    protected void beforeStamp(T t) {}

    /**
     * Save {@code t}'s companions — variant pools, container links. Called once with
     * {@code toSource = false} after the user-tier write (a failure is logged, the save stands)
     * and once with {@code true} as the best-effort sidecar of the source write.
     */
    protected void sidecars(T t, CarriageDims dims, boolean toSource) throws IOException {}

    /** The plots a stamping {@link #enter} restamps: just {@code t}, or its whole row. */
    protected List<T> stampOnEnter(T t, CarriageDims dims) {
        return List.of(t);
    }

    /** The {@code editor_action} advancement id a save of {@code t} triggers, or null. */
    protected @Nullable String advancement(T t) {
        return null;
    }

    /** Runs after the player has landed in {@code t}'s plot. */
    protected void afterEnter(ServerPlayer player, T t) {}

    /** Runs after the user-tier write and sidecars of {@code t} have landed, before the source write. */
    protected void afterSave(ServerPlayer player, T t, CarriageDims dims) {}

    // ---- enter -------------------------------------------------------------------------------

    /**
     * The three ways into a plot and the one decision between them: whether to restamp first.
     *
     * <p>A reload always restamps — every command and post-download jump means this, whether or not
     * the player is already standing in the plot, because a relay Load that replaced the file on
     * disk must show the new blocks. A walk (the X menu's Go here, the panel's Enter) restamps only
     * when the player is not already standing in the plot: restamping a plot you are standing in
     * would throw away every unsaved edit for the sake of a few blocks' teleport.</p>
     */
    record PlotEntry(boolean onTop, boolean stamp, EditorPlotArrival.Inside inside) {
        static PlotEntry reload(boolean onTop) {
            return new PlotEntry(onTop, true, EditorPlotArrival.Inside.FRONT_DOOR);
        }

        static PlotEntry walk(boolean onTop, boolean standingIn) {
            return new PlotEntry(onTop, !standingIn, EditorPlotArrival.Inside.FRONT_DOOR);
        }

        static PlotEntry inside(EditorPlotArrival.Inside inside, boolean standingIn) {
            return new PlotEntry(false, !standingIn, inside);
        }
    }

    /** Teleport to {@code t}'s plot on the roof, restamping first. */
    public final void enter(ServerPlayer player, T t) {
        enter(player, t, true);
    }

    /** Teleport to {@code t}'s plot, restamping first — the reload every command means. */
    public final void enter(ServerPlayer player, T t, boolean onTop) {
        enter(player, t, PlotEntry.reload(onTop));
    }

    /** The X menu's Go here: a walk, restamping only when the player is not already inside. */
    public final void walkTo(ServerPlayer player, T t, boolean onTop) {
        enter(player, t, PlotEntry.walk(onTop, EditorPlotScope.standingIn(player, t)));
    }

    /** The panel's Enter: land inside at {@code inside}, restamping unless already standing in the plot. */
    public final void enterInside(ServerPlayer player, T t, EditorPlotArrival.Inside inside) {
        enter(player, t, PlotEntry.inside(inside, EditorPlotScope.standingIn(player, t)));
    }

    /**
     * @param stamp whether to erase + restamp before teleporting. The category entry passes
     *              {@code false}: it has stamped, or queued, every plot itself.
     */
    public final void enter(ServerPlayer player, T t, boolean onTop, boolean stamp) {
        enter(player, t, new PlotEntry(onTop, stamp, EditorPlotArrival.Inside.FRONT_DOOR));
    }

    /**
     * @param inside where an {@code onTop == false} landing aims: the -X doorway facing in, or the
     *               centre. A doorless plot lands at the centre whatever it says.
     */
    public final void enter(ServerPlayer player, T t, boolean onTop, boolean stamp,
                            EditorPlotArrival.Inside inside) {
        enter(player, t, new PlotEntry(onTop, stamp, inside));
    }

    private void enter(ServerPlayer player, T t, PlotEntry entry) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = origin(t, dims);
        if (origin == null) {
            LOGGER.warn("[DungeonTrain] {} editor enter: unknown template '{}'", category().displayName(), describe(t));
            return;
        }

        EditorSessions.rememberReturn(player);
        if (entry.stamp()) {
            // A category fill still in flight must land before a restamp walks the same plots.
            EditorStampQueue.flush();
            for (T each : stampOnEnter(t, dims)) stampPlot(overworld, each, dims);
        }

        Vec3i footprint = size(t, dims);
        EditorPlotArrival.land(player, overworld, origin, footprint, entry.onTop(), entry.inside(),
            door(origin, t, dims));
        afterEnter(player, t);

        LOGGER.info("[DungeonTrain] {} editor enter: {} -> {} at {} ({})",
            category().displayName(), player.getName().getString(), describe(t), origin,
            entry.onTop() ? "top" : "inside");
    }

    // ---- stamp / clear -----------------------------------------------------------------------

    /** Erase, place, cage and snapshot {@code t}'s plot. Idempotent; a model with no plot is skipped. */
    public final void stampPlot(ServerLevel level, T t, CarriageDims dims) {
        BlockPos origin = origin(t, dims);
        if (origin == null) return;
        Vec3i size = size(t, dims);
        beforeStamp(t);
        erase(level, origin, t, dims);
        // Cage included: a mob left standing on the bedrock would survive a footprint-only sweep.
        EditorPlotEntityClearer.discardNonPlayersIn(level, origin.offset(-1, -1, -1),
            new Vec3i(size.getX() + 2, size.getY() + 2, size.getZ() + 2));
        place(level, origin, t, dims);
        EditorPlotCage.setOutline(level, origin, size, EditorPlotCage.OUTLINE_BLOCK);
        captureSnapshot(level, origin, t, dims);
    }

    /** Erase {@code t}'s plot — footprint + cage to air — and drop its dirty-check baseline. */
    public final void clearPlot(ServerLevel level, T t, CarriageDims dims) {
        BlockPos origin = origin(t, dims);
        if (origin == null) return;
        erase(level, origin, t, dims);
        EditorPlotSnapshots.clear(snapshotKey(t));
    }

    /** Stamp every resident plot now, after any category fill in flight has landed. */
    public final void stampAll(ServerLevel level, CarriageDims dims) {
        EditorStampQueue.flush();
        for (T t : resident(dims)) stampPlot(level, t, dims);
    }

    /** One stamp job per resident plot, for a category fill to run across ticks. */
    public final List<EditorStampQueue.Job> stampAllJobs(ServerLevel level, CarriageDims dims) {
        List<EditorStampQueue.Job> jobs = new ArrayList<>();
        for (T t : resident(dims)) {
            jobs.add(new EditorStampQueue.Job("stamp " + describe(t), () -> stampPlot(level, t, dims),
                EditorCategory.plotBoxOf(level, t, dims)));
        }
        return jobs;
    }

    /** Erase every resident plot. */
    public final void clearAll(ServerLevel level, CarriageDims dims) {
        for (T t : resident(dims)) clearPlot(level, t, dims);
    }

    private void captureSnapshot(ServerLevel level, BlockPos origin, T t, CarriageDims dims) {
        Vec3i size = size(t, dims);
        EditorPlotSnapshots.capture(snapshotKey(t), level, origin, size.getX(), size.getY(), size.getZ());
    }

    // ---- locate ------------------------------------------------------------------------------

    /**
     * The resident model whose plot box (cage and roof headroom included, see {@link EditorPlotBox})
     * holds {@code pos}. Empty unless {@link #category()} is the resident one: every category shares
     * the editor origin, so a non-resident kind's predicted plots would match the stamped ones.
     */
    public final Optional<T> plotContaining(BlockPos pos, CarriageDims dims) {
        if (!EditorStampedCategoryState.isActive(category())) return Optional.empty();
        return locate(pos, resident(dims), t -> origin(t, dims), t -> size(t, dims));
    }

    /** The containment walk behind {@link #plotContaining}, without the residency gate or a world. */
    static <T> Optional<T> locate(BlockPos pos, List<T> models, Function<T, BlockPos> origin,
                                  Function<T, Vec3i> size) {
        for (T t : models) {
            BlockPos o = origin.apply(t);
            if (o != null && EditorPlotBox.contains(pos, o, size.apply(t))) return Optional.of(t);
        }
        return Optional.empty();
    }

    // ---- save --------------------------------------------------------------------------------

    /** {@link #save(ServerPlayer, Template)} for the plot {@code player} is standing in. */
    public final SaveResult save(ServerPlayer player) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        CarriageDims dims = DungeonTrainWorldData.get(server.overworld()).dims();
        T t = plotContaining(player.blockPosition(), dims).orElseThrow(() ->
            new IOException("Player is not inside any " + category().displayName().toLowerCase() + " plot."));
        return save(player, t);
    }

    /**
     * Capture {@code t}'s plot and persist it: the user tier, its sidecars, the dirty-check
     * baseline, the advancement and relay hooks, then the source tree when dev mode is on.
     */
    public final SaveResult save(ServerPlayer player, T t) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = origin(t, dims);
        if (origin == null) throw new IOException("Unknown template '" + describe(t) + "'.");

        StructureTemplate template = capture(overworld, origin, t, dims);
        persist(t, template, false);
        try {
            sidecars(t, dims, false);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] {} editor save: sidecar save failed for {}: {}",
                category().displayName(), describe(t), e.toString());
        }

        // Refresh the dirty-check baseline so the just-saved state reads as clean.
        captureSnapshot(overworld, origin, t, dims);
        afterSave(player, t, dims);

        String advancement = advancement(t);
        if (advancement != null) ModAdvancementTriggers.EDITOR_ACTION.get().trigger(player, advancement);
        // …and, when they have opted in, to the player's relay profile. Hooked here rather than at
        // the callers because every way in lands on this method — see EditorRelaySave.
        EditorRelaySave.afterSave(player, t);
        LOGGER.info("[DungeonTrain] {} editor save: {} -> {}",
            category().displayName(), player.getName().getString(), describe(t));

        return EditorSave.toSource(category().displayName() + " editor save: " + describe(t),
            () -> persist(t, template, true),
            () -> sidecars(t, dims, true));
    }
}
