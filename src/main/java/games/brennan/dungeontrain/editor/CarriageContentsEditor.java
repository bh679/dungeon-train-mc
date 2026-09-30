package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageDoorCells;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.train.ContentsSize;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.template.Template;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Editor plots for {@link CarriageContents} — fixed overworld locations at
 * {@code z = 80} (offset from the carriage row at {@code z = 0}, pillar row at
 * {@code z = 40}) where OPs build interior layouts. Each plot stamps a chosen
 * carriage shell as non-editable context so the author can see how the
 * contents fit inside walls + floor + ceiling; only the interior volume is
 * captured on save.
 *
 * <p>Reuses the {@link CarriageEditor} session map via
 * {@link CarriageEditor#rememberReturn} so a single
 * {@code /dungeontrain editor exit} command restores the player regardless of
 * which editor they entered. Same pattern as {@link PillarEditor}.</p>
 */
public final class CarriageContentsEditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Contents row Z-origin — sourced from {@link
     * EditorLayout#CONTENTS_FIRST_Z}: the origin every category shares.
     * The row overlaps the carriage row and the parts grid in plan view;
     * that is fine because a category switch erases the previous
     * category's plots before this one is stamped, and
     * {@link #plotContaining} answers only while CONTENTS is resident.
     */
    private static final int PLOT_Z = EditorLayout.CONTENTS_FIRST_Z;
    private static final int FIRST_PLOT_X = 0;

    /** Alias kept for readability — the Z-axis tight gap used inside a sub-variant column. */
    private static final int SUB_VARIANT_GAP = EditorLayout.SUB_VARIANT_GAP;

    private static final BlockState OUTLINE_BLOCK = Blocks.BEDROCK.defaultBlockState();

    /** Fallback shell variant stamped as context when the user doesn't specify one. */
    private static final CarriageVariant DEFAULT_SHELL = CarriageVariant.of(CarriageType.STANDARD);

    public record SaveResult(boolean sourceAttempted, boolean sourceWritten, String sourceError) {
        public static SaveResult skipped() { return new SaveResult(false, false, null); }
        public static SaveResult written() { return new SaveResult(true, true, null); }
        public static SaveResult failed(String error) { return new SaveResult(true, false, error); }
    }

    private CarriageContentsEditor() {}

    /**
     * The box a contents variant's plot occupies.
     *
     * <p>Every one but the portal corridor's is a plain carriage box. Delegates to
     * {@link CarriageContentsPlacer#contentsDims} rather than repeating the rule — the plot, the
     * template captured out of it and the interior the dirty-check compares must all be the same
     * box.</p>
     *
     * <p><b>Only for sizing the plot box.</b> Never hand it back to {@link CarriagePlacer#placeAt} or
     * {@link CarriageContentsPlacer#placeAt}: both resolve their own box from the variant / contents
     * id, so an already-resolved one would be grown twice.</p>
     */
    public static CarriageDims plotDims(CarriageContents contents, CarriageDims dims) {
        return CarriageContentsPlacer.contentsDims(contents, dims);
    }

    /**
     * The {@code +X} step between plots of {@code size} — that size's box plus a {@link
     * EditorLayout#GAP}. Only one size is standing at a time ({@link ContentsResidentSize}), so a
     * row is spaced for its own box rather than the widest any contents can be.
     */
    private static int plotStep(ContentsSize size, CarriageDims dims) {
        return size.boxOrRoom(dims, groupSize()).length() + EditorLayout.GAP;
    }

    private static int groupSize() {
        return games.brennan.dungeontrain.config.DungeonTrainConfig.getGroupSize();
    }

    /**
     * The shell stamped around a contents plot for visual context.
     *
     * <p>A portal corridor's own contents (and their sub-variants) get their <b>corridor</b> — they
     * are authored to sit inside one, and against anything else they would show none of the walkway,
     * baffles or lantern floor they have to work around.</p>
     *
     * <p>Everything else stands in a carriage the train would really put it in: one <b>of its size</b>
     * whose contents list has it enabled, drawn by carriage weight — the rule Test the Carriage
     * follows too, via {@link games.brennan.dungeontrain.train.ContentsShellPicker}. Fixed per
     * template, so the plot shows the same carriage every visit. When no carriage of that size
     * enables it, {@link #fallbackShell} stands in (the plot still needs a shell).</p>
     */
    public static CarriageVariant shellFor(CarriageContents contents) {
        // A portal corridor's own furnishing stands in its corridor — the long one for `portal`
        // (Half), the short one for `portal_short` (Room-sized, but no ordinary carriage takes it).
        PortalCorridorKind kind = CarriageContentsPlacer.portalCorridorKindOf(contents);
        if (kind != null) return PortalCarriageBuilder.portalVariant(kind);
        ContentsSize size = CarriageContentsPlacer.sizeOf(contents);
        CarriageVariant fallback = fallbackShell(size);
        try {
            return games.brennan.dungeontrain.train.ContentsShellPicker.stableFor(contents.id())
                .filter(shell -> CarriagePlacer.sizeOf(shell) == size)
                .orElse(fallback);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Contents plot shell for '{}' fell back to {}: {}",
                contents.id(), fallback.id(), e.toString());
            return fallback;
        }
    }

    /**
     * The shell a plot of {@code size} stands in when no carriage of that size enables its contents:
     * the standard carriage, the long portal corridor, or the first Full shell registered (the
     * standard carriage when there is none yet, which the Full box's size gate leaves empty).
     */
    private static CarriageVariant fallbackShell(ContentsSize size) {
        return switch (size) {
            case ROOM -> DEFAULT_SHELL;
            case HALF -> PortalCarriageBuilder.portalVariant(PortalCorridorKind.LONG);
            case FULL -> CarriageVariantRegistry.allVariants().stream()
                .filter(v -> CarriagePlacer.sizeOf(v) == ContentsSize.FULL)
                .findFirst().orElse(DEFAULT_SHELL);
        };
    }

    /**
     * Re-stamp the plot for {@code contents} in-place: fresh shell, fresh
     * contents, fresh barrier cage. Used by {@code runEnterCategory(CONTENTS)}
     * to materialise every registered contents plot at once so the player can
     * walk between them, mirroring the carriages/tracks category enter flow.
     */
    public static void stampPlot(ServerLevel overworld, CarriageContents contents, CarriageDims dims) {
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) return;
        // Drop in-session contents-store changes (loot-prefab links) so the
        // re-stamp reads the last-saved disk state; placement writes only
        // touch the in-memory cache until /save.
        ContainerContentsStore.invalidate("contents:" + contents.id());
        // The plot's own box — longer than a carriage for the portal corridor's contents.
        // The two placers take the WORLD's dims and resolve their own box from the variant /
        // contents id; only the box operations take the resolved one. See plotDims.
        CarriageDims box = plotDims(contents, dims);
        CarriagePlacer.eraseAt(overworld, origin, box);
        CarriageContentsPlacer.eraseAt(overworld, origin, box);
        CarriagePlacer.placeAt(overworld, origin, shellFor(contents), dims);
        CarriageContentsPlacer.placeAt(overworld, origin, contents, dims);
        setOutline(overworld, origin, OUTLINE_BLOCK, box);

        // Snapshot the freshly-stamped INTERIOR for the dirty-check baseline.
        // Save's captureTemplate captures only the interior (size = dims-2),
        // so the snapshot must use the same region — comparing the live
        // interior to a snapshot of just the interior keeps shell blocks
        // (which the contents save deliberately excludes) out of the diff.
        BlockPos interiorOrigin = origin.offset(1, 1, 1);
        Vec3i interior = CarriageContentsPlacer.interiorSize(box);
        EditorPlotSnapshots.capture(
            EditorPlotSnapshots.key("contents", contents.id()),
            overworld, interiorOrigin, interior.getX(), interior.getY(), interior.getZ()
        );
    }

    /**
     * Every registered contents of the {@link ContentsResidentSize resident size} — the ones whose
     * plots are standing. Room, Half and Full are separate template types sharing one origin, so
     * anything that walks "the contents plots" walks these.
     */
    /**
     * {@code editor contents enter size.<key>} — "show this size", for a size tab whose size has no
     * template to jump to. Contents ids cannot contain a dot, so the token can never be a name.
     */
    public static final String SIZE_TOKEN_PREFIX = "size.";

    public static List<CarriageContents> residentContents() {
        ContentsSize resident = ContentsResidentSize.current();
        List<CarriageContents> out = new java.util.ArrayList<>();
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            if (CarriageContentsPlacer.sizeOf(c) == resident) out.add(c);
        }
        return out;
    }

    /**
     * Make {@code target}'s size the resident one before its plot is entered. When another size is
     * standing, its plots are erased and this size's are repopulated — the way a category switch
     * works, and for the same reason: every size lays out from the same origin. Anything under
     * {@code target}'s plot is erased now; the rest, and every other plot of the new size, queue.
     *
     * @return true when the size changed, so the caller must stamp {@code target}'s plot itself
     */
    public static boolean ensureResident(ServerLevel overworld, CarriageContents target, CarriageDims dims) {
        return ensureResident(overworld, CarriageContentsPlacer.sizeOf(target), target, dims);
    }

    /**
     * {@link #ensureResident(ServerLevel, CarriageContents, CarriageDims)} for a size, with an
     * optional {@code target} to leave for the caller to stamp — null when the size is shown
     * without entering any plot (a size tab with no templates yet).
     */
    public static boolean ensureResident(ServerLevel overworld, ContentsSize size,
                                         @org.jetbrains.annotations.Nullable CarriageContents target,
                                         CarriageDims dims) {
        if (size == ContentsResidentSize.current()) return false;
        if (!EditorStampedCategoryState.isActive(EditorCategory.CONTENTS)) {
            // Nothing of the old size is standing; the category entry stamps the new one.
            ContentsResidentSize.set(overworld, size);
            return false;
        }
        List<EditorStampQueue.Job> erases = new java.util.ArrayList<>();
        for (CarriageContents c : residentContents()) {
            erases.add(new EditorStampQueue.Job("erase contents " + c.id(),
                () -> CarriageStampGuard.run(() -> clearPlot(overworld, c, dims)),
                EditorCategory.plotBoxOf(overworld, new Template.Contents(c), dims)));
        }
        net.minecraft.world.level.levelgen.structure.BoundingBox headBox = target == null ? null
            : EditorCategory.plotBoxOf(overworld, new Template.Contents(target), dims);
        EditorStampQueue.Partition split = EditorStampQueue.partitionOverlapping(erases, headBox);
        for (EditorStampQueue.Job job : split.overlapping()) job.work().run();

        ContentsResidentSize.set(overworld, size);
        List<EditorStampQueue.Job> queued = new java.util.ArrayList<>(split.rest());
        if (!erases.isEmpty()) queued.add(EditorCategory.layerSweepJob(overworld, dims, headBox));
        for (CarriageContents c : residentContents()) {
            if (target != null && c.id().equals(target.id())) continue;
            queued.add(new EditorStampQueue.Job("stamp contents " + c.id(),
                () -> CarriageStampGuard.run(() -> stampPlot(overworld, c, dims))));
        }
        EditorStampQueue.start(queued, "contents " + size.key());
        LOGGER.info("[DungeonTrain] Contents editor: switched to {} contents ({} plot(s) queued)",
            size.key(), queued.size());
        return true;
    }

    /** A plot as it stood: where, and how big. Captured before a delete moves the row. */
    public record StandingPlot(BlockPos origin, CarriageDims box) {}

    /**
     * Every plot in {@code size}'s row from top-level slot {@code fromSlot} on — parents and their
     * sub-variant columns. Call <b>before</b> {@link CarriageContentsRegistry#unregister}, so
     * {@link #restampRowAfterDeletion} can clear exactly where the shifted plots used to be.
     */
    public static List<StandingPlot> plotsFrom(ContentsSize size, int fromSlot, CarriageDims dims) {
        List<StandingPlot> out = new java.util.ArrayList<>();
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            if (!inRowFrom(c, size, fromSlot)) continue;
            BlockPos o = plotOrigin(c, dims);
            if (o != null) out.add(new StandingPlot(o, plotDims(c, dims)));
        }
        return out;
    }

    /**
     * Close the gap a deleted top-level contents left in {@code size}'s row: erase every plot
     * {@link #plotsFrom} captured, then restamp everything now at slot {@code fromSlot} or later —
     * the templates that slid left one slot, with their sub-variant columns. Must be called
     * <b>after</b> {@link CarriageContentsRegistry#unregister}.
     */
    public static void restampRowAfterDeletion(ServerLevel level, ContentsSize size, int fromSlot,
                                               List<StandingPlot> before, CarriageDims dims) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (StandingPlot plot : before) {
            CarriagePlacer.eraseAt(level, plot.origin(), plot.box());
            CarriageContentsPlacer.eraseAt(level, plot.origin(), plot.box());
            setOutline(level, plot.origin(), air, plot.box());
        }
        for (CarriageContents c : CarriageContentsRegistry.allContents()) {
            if (inRowFrom(c, size, fromSlot)) stampPlot(level, c, dims);
        }
    }

    /** True for a contents in {@code size}'s row whose column is at top-level slot {@code fromSlot} or later. */
    private static boolean inRowFrom(CarriageContents c, ContentsSize size, int fromSlot) {
        if (CarriageContentsPlacer.sizeOf(c) != size) return false;
        String columnId = CarriageContentsGroupStore.findParentOf(c.id()).orElse(c.id());
        Integer slot = topLevelSlotIndex().get(columnId);
        return slot != null && slot >= fromSlot;
    }

    /**
     * Erase the plot for {@code contents} — shell + interior back to air,
     * barrier cage removed. Called by {@code EditorCategory.clearAllPlots}
     * when switching categories.
     */
    public static void clearPlot(ServerLevel overworld, CarriageContents contents, CarriageDims dims) {
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) return;
        CarriageDims box = plotDims(contents, dims);
        CarriagePlacer.eraseAt(overworld, origin, box);
        CarriageContentsPlacer.eraseAt(overworld, origin, box);
        // Drop the dirty-check baseline — same reasoning as CarriageEditor.clearPlot.
        EditorPlotSnapshots.clear(EditorPlotSnapshots.key("contents", contents.id()));
        setOutline(overworld, origin, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), box);
    }

    /**
     * Plot origin for {@code contents}. Two-axis layout:
     *
     * <ul>
     *   <li><b>Top-level variants</b> sit in a single +X row at
     *       {@code z = PLOT_Z}, separated by
     *       {@code dims.length() + EditorLayout.GAP} (3 air blocks between
     *       cages). Group children are excluded from this row — they don't
     *       claim a +X slot.</li>
     *   <li><b>Sub-variants</b> stack along +Z from their parent's slot,
     *       sharing the parent's X. Each member sits at
     *       {@code z = PLOT_Z + (memberIndex + 1) * (dims.width() + SUB_VARIANT_GAP)}
     *       so the first member is one row +Z from the parent, the second
     *       two rows down, etc.</li>
     * </ul>
     *
     * <p>A group's column runs as far along {@code +Z} as it has members —
     * nothing else is laid out past it while CONTENTS is resident, so there
     * is no cap to overflow.</p>
     */
    public static BlockPos plotOrigin(CarriageContents contents, CarriageDims dims) {
        String target = contents.id();
        // Is the target a member of some group? If so, position relative to
        // its parent's slot.
        java.util.Optional<String> parentOf = CarriageContentsGroupStore.findParentOf(target);
        if (parentOf.isPresent()) {
            String parentId = parentOf.get();
            int memberIndex = memberIndexIn(parentId, target);
            if (memberIndex < 0) return null;
            BlockPos parentOrigin = topLevelPlotOrigin(parentId, dims);
            if (parentOrigin == null) return null;
            int zStep = dims.width() + SUB_VARIANT_GAP;
            return new BlockPos(
                parentOrigin.getX(),
                parentOrigin.getY(),
                parentOrigin.getZ() + (memberIndex + 1) * zStep
            );
        }
        return topLevelPlotOrigin(target, dims);
    }

    /**
     * Top-level variants only — children of any group are excluded from this
     * row so their parent's slot stays at the position it would have had
     * without them. Walks {@link CarriageContentsRegistry#allContents} in
     * registry order, skipping any id in
     * {@link CarriageContentsGroupStore#allChildIds}.
     */
    private static BlockPos topLevelPlotOrigin(String targetId, CarriageDims dims) {
        Integer index = topLevelSlotIndex().get(targetId);
        if (index == null) return null;
        ContentsSize size = CarriageContentsPlacer.sizeOf(targetId);
        return new BlockPos(FIRST_PLOT_X + index * plotStep(size, dims), EditorLayout.PLOT_Y, PLOT_Z);
    }

    // ---- id → +X slot index, memoised on (registry snapshot, group child set) ------------------

    private static List<CarriageContents> slotIndexSource;
    private static java.util.Set<String> slotIndexChildren;
    private static int slotIndexSizesVersion = -1;
    private static java.util.Map<String, Integer> SLOT_INDEX = java.util.Map.of();

    /**
     * Top-level contents id → row slot (group children claim no slot). This was the editor's
     * hottest frame: the overlay resolves every template's origin every tick, and the linear walk
     * here — over a registry list that was itself rebuilt per call — made a pass O(n²) with
     * n≈230. Both inputs are now immutable objects that the registry / group store replace on
     * mutation, so two reference compares decide whether the map is stale.
     */
    private static synchronized java.util.Map<String, Integer> topLevelSlotIndex() {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        java.util.Set<String> children = CarriageContentsGroupStore.allChildIds();
        int sizesVersion = TemplateSizeStore.CONTENTS.version();
        if (all == slotIndexSource && children == slotIndexChildren && sizesVersion == slotIndexSizesVersion) {
            return SLOT_INDEX;
        }
        SLOT_INDEX = slotIndex(all, children, id -> TemplateSizeStore.CONTENTS.sizeOf(id));
        slotIndexSource = all;
        slotIndexChildren = children;
        slotIndexSizesVersion = sizesVersion;
        return SLOT_INDEX;
    }

    /**
     * Top-level id → slot within its own size's row, in registry order. Each size counts from zero:
     * the rows are separate shelves, so a Full template is the first plot of its row, not the
     * two-hundredth. Pure, so the layout is testable without a world.
     */
    static java.util.Map<String, Integer> slotIndex(List<CarriageContents> all, java.util.Set<String> children,
                                                    java.util.function.Function<String, ContentsSize> sizeOf) {
        java.util.Map<String, Integer> index = new java.util.HashMap<>(all.size() * 2);
        int[] next = new int[ContentsSize.values().length];
        for (CarriageContents c : all) {
            if (children.contains(c.id())) continue;
            if (index.containsKey(c.id())) continue;
            index.put(c.id(), next[sizeOf.apply(c.id()).ordinal()]++);
        }
        return java.util.Map.copyOf(index);
    }

    /** Index of {@code memberId} in {@code parentId}'s group, or {@code -1} if absent. */
    private static int memberIndexIn(String parentId, String memberId) {
        java.util.Optional<games.brennan.dungeontrain.train.CarriageContentsGroup> g =
            CarriageContentsGroupStore.get(parentId);
        if (g.isEmpty()) return -1;
        java.util.List<games.brennan.dungeontrain.train.CarriageContentsGroup.Member> members = g.get().members();
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).id().equals(memberId)) return i;
        }
        return -1;
    }


    /**
     * Returns the contents whose plot contains {@code pos} (within the
     * footprint plus 1-block outline margin), or {@code null} if none. Matches
     * the signature of {@link CarriageEditor#plotContaining} so
     * {@code EditorCommand} can dispatch on the same {@link CarriageDims}.
     */
    public static CarriageContents plotContaining(BlockPos pos, CarriageDims dims) {
        // Answers only while CONTENTS is the resident category — every category shares the origin.
        if (!EditorStampedCategoryState.isActive(EditorCategory.CONTENTS)) return null;
        // …and only for the resident size: every size lays out from the same origin too.
        for (CarriageContents contents : residentContents()) {
            BlockPos o = plotOrigin(contents, dims);
            if (o == null) continue;
            CarriageDims box = plotDims(contents, dims);
            // +2 Y headroom above cage top — see CarriageEditor.plotContaining for rationale.
            if (pos.getX() >= o.getX() - 1 && pos.getX() <= o.getX() + box.length()
                && pos.getY() >= o.getY() - 1 && pos.getY() <= o.getY() + box.height() + 2
                && pos.getZ() >= o.getZ() - 1 && pos.getZ() <= o.getZ() + box.width()) {
                return contents;
            }
        }
        return null;
    }

    /**
     * Teleport {@code player} to the plot for {@code contents}: save return
     * position, clear the footprint, stamp the chosen {@code shellVariant}
     * (visual context — walls/floor/ceiling), then stamp the current contents
     * template on top. Finally draw the barrier cage and teleport inside.
     *
     * <p>The shell blocks are not protected — if the author breaks a wall it
     * won't affect the saved contents template (save captures only the
     * interior volume). Re-entering will re-stamp the shell.</p>
     */
    public static void enter(ServerPlayer player, CarriageContents contents, CarriageVariant shellVariant) {
        enter(player, contents, shellVariant, true);
    }

    /**
     * Always restamps: this is the reload every command and post-download jump means, whether or
     * not the player is already standing in the plot — a relay Load that replaced the file on disk
     * arrives here and must show the new blocks. The walk that keeps unsaved edits is
     * {@link #walkTo} / {@link #enterInside}.
     */
    public static void enter(ServerPlayer player, CarriageContents contents, CarriageVariant shellVariant, boolean onTop) {
        enter(player, contents, shellVariant, onTop, true);
    }

    /**
     * The X menu's Go here: a walk to the plot under its natural shell, not a reload — restamps
     * only when the player is not already standing in it.
     */
    public static void walkTo(ServerPlayer player, CarriageContents contents, boolean onTop) {
        enter(player, contents, null, onTop, !EditorPlotScope.standingIn(player, new Template.Contents(contents)));
    }

    /**
     * The panel's Enter button: land inside at {@code inside} under the contents' natural shell,
     * restamping unless the player is already standing in this plot.
     */
    public static void enterInside(ServerPlayer player, CarriageContents contents, EditorPlotArrival.Inside inside) {
        enter(player, contents, null, false, !EditorPlotScope.standingIn(player, new Template.Contents(contents)), inside);
    }

    /**
     * @param stamp whether to erase + restamp the shell and contents before teleporting. The
     *              category entry passes {@code false}: it has just stamped this plot itself.
     */
    public static void enter(ServerPlayer player, CarriageContents contents, CarriageVariant shellVariant,
                             boolean onTop, boolean stamp) {
        enter(player, contents, shellVariant, onTop, stamp, EditorPlotArrival.Inside.FRONT_DOOR);
    }

    /**
     * @param inside where an {@code onTop == false} landing aims: the -X doorway facing in, or the
     *               centre. Either way it steps to the nearest free column if that cell is built up.
     */
    public static void enter(ServerPlayer player, CarriageContents contents, CarriageVariant shellVariant,
                             boolean onTop, boolean stamp, EditorPlotArrival.Inside inside) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) {
            LOGGER.warn("[DungeonTrain] Contents editor enter: unknown contents '{}'", contents.id());
            return;
        }
        // An explicit shell wins; otherwise the contents' natural one — the corridor for the portal
        // contents, a standard carriage for everything else.
        CarriageVariant shell = shellVariant != null ? shellVariant : shellFor(contents);
        CarriageDims box = plotDims(contents, dims);

        CarriageEditor.rememberReturn(player);

        // A template of another size means switching the resident size first — which leaves this
        // plot to be stamped here, whatever the caller asked.
        if (ensureResident(overworld, contents, dims)) stamp = true;

        if (stamp) {
            CarriagePlacer.eraseAt(overworld, origin, box);
            // Also discard any entities left from a previous edit session
            // (armor stands / item frames / paintings don't get cleared by the
            // block-only erase above). Must run before the shell + contents stamp
            // so the freshly stamped NBT entities don't get caught up in this.
            CarriageContentsPlacer.eraseAt(overworld, origin, box);
            // Stamp the shell first — this fills floor/walls/ceiling as context.
            // Uses the 4-arg placeAt so variant-block sidecar entries don't get
            // applied here (the author is editing contents, not the shell).
            CarriagePlacer.placeAt(overworld, origin, shell, dims);
            // Stamp the current contents template on top of the air interior.
            CarriageContentsPlacer.placeAt(overworld, origin, contents, dims);
            setOutline(overworld, origin, OUTLINE_BLOCK, box);
            // The dirty-check baseline, as stampPlot takes it: a plot restamped here without one
            // could never report an edit as unsaved.
            Vec3i interior = CarriageContentsPlacer.interiorSize(box);
            EditorPlotSnapshots.capture(EditorPlotSnapshots.key("contents", contents.id()),
                overworld, origin.offset(1, 1, 1), interior.getX(), interior.getY(), interior.getZ());
        }

        Vec3i footprint = new Template.Contents(contents).plotSize(dims);
        BlockPos door = EditorPlotArrival.firstOrNull(CarriageDoorCells.doorBases(origin, box));
        EditorPlotArrival.land(player, overworld, origin, footprint, onTop, inside, door);

        LOGGER.info("[DungeonTrain] Contents editor enter: {} -> {} (shell={}) plot at {} dims={}x{}x{} ({})",
            player.getName().getString(), contents.id(), shell.id(), origin,
            box.length(), box.width(), box.height(), onTop ? "top" : "inside");
    }

    /**
     * Capture the interior volume at the plot for {@code contents} into a
     * fresh {@link StructureTemplate} and persist it via
     * {@link CarriageContentsStore}. Shell blocks are outside the captured
     * region so are naturally excluded — no shell-protection logic needed at
     * save time. When {@link EditorDevMode} is on, the template is also
     * written to the source tree so it ships with the next build.
     */
    public static SaveResult save(ServerPlayer player, CarriageContents contents) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(contents, dims);
        if (origin == null) throw new IOException("Unknown contents '" + contents.id() + "'.");

        // The contents' own box — captures the whole corridor interior for the portal contents
        // rather than its first seven blocks, which the size gate would then reject on load.
        StructureTemplate template = CarriageContentsPlacer.captureTemplate(
            overworld, origin, plotDims(contents, dims));
        CarriageContentsStore.save(contents, template);

        // Contents store: persist any in-session loot-prefab link changes
        // accumulated since enter (PrefabUseHandler defers its writes until
        // /save). Failure is logged but doesn't fail the whole save.
        try {
            ContainerContentsStore.loadFor("contents:" + contents.id()).save();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Contents editor save: contents-store save failed for {}: {}",
                contents.id(), e.toString());
        }

        // Refresh the dirty-check baseline so the just-saved state reads as
        // clean on the next /dt editor unsaved-list query.
        BlockPos interiorOrigin = origin.offset(1, 1, 1);
        Vec3i interiorSnapshotSize = CarriageContentsPlacer.interiorSizeFor(contents, dims);
        EditorPlotSnapshots.capture(
            EditorPlotSnapshots.key("contents", contents.id()),
            overworld, interiorOrigin,
            interiorSnapshotSize.getX(), interiorSnapshotSize.getY(), interiorSnapshotSize.getZ()
        );

        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "saved_contents_variant");
        // …and, when they have opted in, to the player's relay profile. Hooked here rather than at
        // the callers because both ways in land on this method — see EditorRelaySave.
        EditorRelaySave.afterSave(player, new Template.Contents(contents));
        LOGGER.info("[DungeonTrain] Contents editor save: {} -> {} template interior={}x{}x{}",
            player.getName().getString(), contents.id(),
            interiorSnapshotSize.getX(), interiorSnapshotSize.getY(), interiorSnapshotSize.getZ());

        if (!EditorDevMode.isEnabled()) return SaveResult.skipped();
        try {
            CarriageContentsStore.saveToSource(contents, template);
            // Promote the variants sidecar too — without this, shift-right-click
            // variant authoring stayed in run/config and was lost on worktree
            // delete (the bug PR #79's vase update silently shipped without).
            Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(contents, dims);
            CarriageContentsVariantBlocks sidecar =
                CarriageContentsVariantBlocks.loadFor(contents, interiorSize);
            sidecar.saveToSource(contents);
            // Promote the container-contents sidecar (per-position links and
            // pools). Without this, chest→loot-prefab references authored in
            // the plot stay in run/config and are lost when the template
            // ships in the next build.
            ContainerContentsStore.loadFor("contents:" + contents.id()).saveToSource();
            return SaveResult.written();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Contents editor save: source write failed for {}: {}", contents.id(), e.toString());
            return SaveResult.failed(e.getMessage());
        }
    }

    /**
     * Create a new custom contents {@code target} whose template is a
     * duplicate of {@code source}'s current geometry. Registers the contents
     * immediately so it gets its own plot on subsequent lookups.
     */
    /**
     * Create a brand-new custom contents {@code target} with an empty interior
     * — registered, allocated, caged, with the {@link #DEFAULT_SHELL} stamped
     * for context but no contents template applied. The author builds the
     * interior from scratch.
     */
    public static BlockPos createBlank(ServerPlayer player, CarriageContents.Custom target) throws IOException {
        return createBlank(player, target, (CarriageContents) null);
    }

    /**
     * A brand-new top-level contents of {@code size}. The size is declared before the template is
     * registered, so its very first plot lookup already lands it in its own size's row.
     */
    public static BlockPos createBlank(ServerPlayer player, CarriageContents.Custom target,
                                       ContentsSize size) throws IOException {
        if (size != ContentsSize.ROOM && !size.available(
                DungeonTrainWorldData.get(player.getServer().overworld()).dims(), groupSize())) {
            throw new IOException("A " + size.key() + " carriage is longer than this world's carriages can be.");
        }
        TemplateSizeStore.CONTENTS.set(target.id(), size);
        try {
            return createBlank(player, target, (CarriageContents) null);
        } catch (IOException | RuntimeException e) {
            TemplateSizeStore.CONTENTS.forget(target.id());
            throw e;
        }
    }

    /**
     * As above, but sized and shelled as {@code boxSource} rather than as {@code target}.
     *
     * <p><b>Why the box cannot come from {@code target}.</b> A brand-new sub-variant is not yet a
     * member of its parent's group at this point, so {@link #plotDims} still answers "carriage" for
     * it — and a blank captured at a carriage interior is rejected by the size gate on every load
     * afterwards. Saving the group membership first would fix the lookup but leave a dangling member
     * if this then threw, so the caller names the source instead and the dependency stays visible.</p>
     *
     * <p>Pass {@code null} for an ordinary top-level contents, which is its own box.</p>
     */
    public static BlockPos createBlank(ServerPlayer player, CarriageContents.Custom target,
                                       CarriageContents boxSource) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        if (!CarriageContentsRegistry.register(target)) {
            throw new IOException("Contents '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            CarriageContentsRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        CarriageContents sizedAs = boxSource != null ? boxSource : target;
        CarriageDims box = plotDims(sizedAs, dims);

        CarriagePlacer.eraseAt(overworld, targetOrigin, box);
        CarriageContentsPlacer.eraseAt(overworld, targetOrigin, box);
        CarriagePlacer.placeAt(overworld, targetOrigin, shellFor(sizedAs), dims);

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, targetOrigin, box);
        CarriageContentsStore.save(target, template);

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, box);

        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "made_contents");
        LOGGER.info("[DungeonTrain] Contents editor createBlank: {} created '{}' at {}",
            player.getName().getString(), target.id(), targetOrigin);
        return targetOrigin;
    }

    public static BlockPos duplicate(ServerPlayer player, CarriageContents source, CarriageContents.Custom target) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        // A copy is its source's box, so it lives in its source's size row.
        TemplateSizeStore.CONTENTS.set(target.id(), CarriageContentsPlacer.sizeOf(source));
        if (!CarriageContentsRegistry.register(target)) {
            throw new IOException("Contents '" + target.id() + "' is already registered.");
        }

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            CarriageContentsRegistry.unregister(target.id());
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }

        // Stamp the default shell as context, then stamp the source contents
        // on top. Capture the interior region and save under the new id.
        CarriageDims sourceBox = plotDims(source, dims);
        CarriagePlacer.eraseAt(overworld, targetOrigin, sourceBox);
        CarriageContentsPlacer.eraseAt(overworld, targetOrigin, sourceBox);
        CarriagePlacer.placeAt(overworld, targetOrigin, shellFor(source), dims);
        CarriageContentsPlacer.placeAt(overworld, targetOrigin, source, dims);

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, targetOrigin, sourceBox);
        CarriageContentsStore.save(target, template);

        // Everything beside the .nbt goes with it — the variants sidecar (random-pick sets,
        // lock-ids, mirror flags), the container links and the weights entry — same path as
        // CarriageEditor.duplicate.
        TemplateCopy.copy(games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind.CONTENTS, null,
            source.id(), target.id());

        setOutline(overworld, targetOrigin, OUTLINE_BLOCK, sourceBox);

        LOGGER.info("[DungeonTrain] Contents editor duplicate: {} created '{}' from '{}' at {}",
            player.getName().getString(), target.id(), source.id(), targetOrigin);
        return targetOrigin;
    }

    /**
     * As {@link #duplicate}, for a copy that belongs in {@code parentId}'s group: it joins the group
     * <b>before</b> anything is stamped, so its first plot is its own place in the parent's column.
     *
     * <p>{@code duplicate} then append — the order {@code editor contents group new} uses — registers
     * the copy as a top-level template first, and stamps it into whichever top-level slot that gives
     * it: another template's plot, drawn over until the next restamp. Appending first costs nothing
     * and lands it where it will stay. The box is {@code source}'s, which is a member of the same
     * group — the size a portal corridor's sub-variants are held to.</p>
     */
    public static BlockPos duplicateIntoGroup(ServerPlayer player, CarriageContents source,
                                              CarriageContents.Custom target, String parentId) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        registerIntoGroup(target, parentId);

        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }
        CarriageDims box = plotDims(source, dims);
        CarriagePlacer.eraseAt(overworld, targetOrigin, box);
        CarriageContentsPlacer.eraseAt(overworld, targetOrigin, box);
        CarriagePlacer.placeAt(overworld, targetOrigin, shellFor(source), dims);
        CarriageContentsPlacer.placeAt(overworld, targetOrigin, source, dims);

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, targetOrigin, box);
        CarriageContentsStore.save(target, template);
        TemplateCopy.copy(games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind.CONTENTS, null,
            source.id(), target.id());
        // Restamp through the ordinary path so the cage and the dirty baseline match every other plot.
        stampPlot(overworld, target, dims);

        LOGGER.info("[DungeonTrain] Contents editor duplicate into group '{}': {} created '{}' from '{}' at {}",
            parentId, player.getName().getString(), target.id(), source.id(), targetOrigin);
        return targetOrigin;
    }

    /**
     * As {@link #duplicateIntoGroup}, for a <b>blank</b> member: it joins {@code parentId}'s group
     * before anything is stamped, so its box is the group's (a sub-variant is its root's size) and
     * its first plot is its own place in the parent's column — never a top-level slot of the Room
     * row, which is what a not-yet-member would answer.
     */
    public static BlockPos createBlankInGroup(ServerPlayer player, CarriageContents.Custom target,
                                              String parentId) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

        registerIntoGroup(target, parentId);
        BlockPos targetOrigin = plotOrigin(target, dims);
        if (targetOrigin == null) {
            throw new IOException("Failed to allocate plot for '" + target.id() + "'.");
        }
        CarriageDims box = plotDims(target, dims);
        CarriagePlacer.eraseAt(overworld, targetOrigin, box);
        CarriageContentsPlacer.eraseAt(overworld, targetOrigin, box);
        CarriagePlacer.placeAt(overworld, targetOrigin, shellFor(target), dims);
        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, targetOrigin, box);
        CarriageContentsStore.save(target, template);
        // Restamp through the ordinary path so the cage and the dirty baseline match every other plot.
        stampPlot(overworld, target, dims);

        games.brennan.dungeontrain.advancement.ModAdvancementTriggers.EDITOR_ACTION.get()
            .trigger(player, "made_contents");
        LOGGER.info("[DungeonTrain] Contents editor createBlank into group '{}': {} created '{}' at {}",
            parentId, player.getName().getString(), target.id(), targetOrigin);
        return targetOrigin;
    }

    /** Register {@code target} and append it to {@code parentId}'s group, undoing the register on failure. */
    private static void registerIntoGroup(CarriageContents.Custom target, String parentId) throws IOException {
        if (!CarriageContentsRegistry.register(target)) {
            throw new IOException("Contents '" + target.id() + "' is already registered.");
        }
        games.brennan.dungeontrain.train.CarriageContentsGroup existing = CarriageContentsGroupStore.get(parentId)
            .orElse(games.brennan.dungeontrain.train.CarriageContentsGroup.EMPTY);
        try {
            CarriageContentsGroupStore.save(parentId, existing.withMember(
                new games.brennan.dungeontrain.train.CarriageContentsGroup.Member(
                    target.id(), games.brennan.dungeontrain.train.CarriageContentsGroup.DEFAULT_WEIGHT)));
        } catch (IOException e) {
            CarriageContentsRegistry.unregister(target.id());
            throw e;
        }
    }

    /** The top-level row slot {@code id} occupies, or -1 for a group member or an unknown id. */
    public static int topLevelSlotOf(String id) {
        Integer index = topLevelSlotIndex().get(id);
        return index == null ? -1 : index;
    }

    /**
     * Save the plot's current interior under a new name — mirrors the
     * rename-on-save behaviour of {@link CarriageEditor#saveAs}. Built-in
     * {@code default} cannot be renamed; customs are moved to the new name.
     */
    public static CarriageContents.Custom saveAs(ServerPlayer player, CarriageContents current, CarriageContents.Custom renamed) throws IOException {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IOException("No server context.");
        ServerLevel overworld = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos origin = plotOrigin(current, dims);
        if (origin == null) throw new IOException("Unknown contents '" + current.id() + "'.");

        StructureTemplate template = CarriageContentsPlacer.captureTemplate(overworld, origin, plotDims(current, dims));

        String oldId;
        // The renamed template is the same box, so it keeps its size (and its row).
        TemplateSizeStore.CONTENTS.set(renamed.id(), CarriageContentsPlacer.sizeOf(current));
        if (current instanceof CarriageContents.Custom currentCustom) {
            if (!CarriageContentsRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            oldId = currentCustom.name();
            CarriageContentsStore.save(renamed, template);
            CarriageContentsVariantBlocks.rename(oldId, renamed.id());
            CarriageContentsRegistry.unregister(oldId);
            CarriageContentsStore.delete(currentCustom);
            CarriageContentsVariantBlocks.invalidate(oldId);
            TemplateSizeStore.CONTENTS.forget(oldId);
            LOGGER.info("[DungeonTrain] Contents editor saveAs (custom→custom): {} renamed '{}' -> '{}'",
                player.getName().getString(), oldId, renamed.id());
        } else if (current instanceof CarriageContents.Builtin builtin) {
            if (!CarriageContentsRegistry.register(renamed)) {
                throw new IOException("Name '" + renamed.id() + "' is already taken.");
            }
            oldId = builtin.id();
            CarriageContentsStore.save(renamed, template);
            CarriageContentsVariantBlocks.rename(oldId, renamed.id());
            CarriageContentsStore.delete(builtin);
            CarriageContentsVariantBlocks.invalidate(oldId);
            LOGGER.info("[DungeonTrain] Contents editor saveAs (builtin→custom): {} saved edits of '{}' as new custom '{}', built-in reverts to fallback",
                player.getName().getString(), oldId, renamed.id());
        } else {
            return renamed;
        }

        // Dev-mode write-through: ship the renamed template + sidecar in the
        // next build, and delete the outgoing-name source files so the rename
        // doesn't leave a stale bundled resource. Soft-fail on any source-tree
        // error — config-dir state is the source of truth.
        if (EditorDevMode.isEnabled()) {
            try {
                CarriageContentsStore.saveToSource(renamed, template);
                Vec3i interiorSize = CarriageContentsPlacer.interiorSizeFor(current, dims);
                CarriageContentsVariantBlocks newSidecar =
                    CarriageContentsVariantBlocks.loadFor(renamed, interiorSize);
                newSidecar.saveToSource(renamed);

                java.nio.file.Path oldNbtSrc = CarriageContentsStore.sourceFileForId(oldId);
                java.nio.file.Files.deleteIfExists(oldNbtSrc);
                java.nio.file.Path oldVariantsSrc = CarriageContentsVariantBlocks.sourcePathForId(oldId);
                if (oldVariantsSrc != null) java.nio.file.Files.deleteIfExists(oldVariantsSrc);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Contents editor saveAs: source write/delete failed for {} -> {}: {}",
                    oldId, renamed.id(), e.toString());
            }
        }

        return renamed;
    }

    /**
     * Barrier cage: 12 edges of a bounding box 1 block outside the
     * {@code length × height × width} footprint. Matches
     * {@link CarriageEditor#setOutline} exactly so the cage geometry is
     * consistent across both editors. Faces are left empty so the player can
     * fly in and out freely.
     */
    private static void setOutline(ServerLevel level, BlockPos origin, BlockState state, CarriageDims dims) {
        int x0 = origin.getX() - 1;
        int y0 = origin.getY() - 1;
        int z0 = origin.getZ() - 1;
        int x1 = origin.getX() + dims.length();
        int y1 = origin.getY() + dims.height();
        int z1 = origin.getZ() + dims.width();

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int extremes = (x == x0 || x == x1 ? 1 : 0)
                        + (y == y0 || y == y1 ? 1 : 0)
                        + (z == z0 || z == z1 ? 1 : 0);
                    if (extremes < 2) continue;
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                }
            }
        }
    }

    /**
     * Helper used by {@code editor contents new <name> [shell_variant]}:
     * resolve the shell context variant for a new-contents call, falling back
     * to {@link #DEFAULT_SHELL} if {@code shellId} is null or missing.
     */
    public static CarriageVariant resolveShellOrDefault(String shellId) {
        if (shellId == null) return DEFAULT_SHELL;
        return CarriageVariantRegistry.find(shellId).orElse(DEFAULT_SHELL);
    }
}
