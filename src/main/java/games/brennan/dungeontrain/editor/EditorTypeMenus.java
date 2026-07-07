package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Server-side enumerator for the floating template-type menus that float at
 * the start of each variant row. One {@link EditorTypeMenusPacket.Menu} per
 * visible row in a category — and each row-start menu also carries the
 * shared template-navigation chrome (category bar across the top, type-tab
 * strip below) so every anchor renders the whole editor surface with the
 * column matching the player's current row expanded.
 *
 * <p>Layout (row → menu anchor):
 * <ul>
 *   <li>X-extending rows (carriages, contents, parts kinds) — anchor sits
 *       one {@link EditorLayout#GAP} past the first plot's {@code -X} cage
 *       edge, at the per-plot label Y, centred over the footprint Z.</li>
 *   <li>Z-extending rows (track tile, pillars, adjuncts, tunnels) — anchor
 *       sits one {@code GAP} past the first plot's {@code -Z} cage edge, at
 *       the per-plot label Y, centred over the footprint X.</li>
 * </ul>
 *
 * <p>Mirrors the parts-fallthrough that
 * {@link EditorPlotLabels#carriageLabels} uses — a CARRIAGES-view menu set
 * includes the carriage-row menu plus all four parts kind menus, since
 * {@code runEnterCategory(CARRIAGES)} stamps both grids together.</p>
 */
public final class EditorTypeMenus {

    /** One {@code GAP} of clear airspace between the first plot's cage edge and the menu anchor. */
    private static final int MENU_GAP = EditorLayout.GAP;
    /** Same Y lift the per-plot labels use — keeps the panel families on the same horizontal plane. */
    private static final int Y_ANCHOR_LIFT = 2;

    private EditorTypeMenus() {}

    /** Every visible type menu for {@code category} at the current world dims. */
    public static List<EditorTypeMenusPacket.Menu> forCategory(EditorCategory category, CarriageDims dims) {
        return switch (category) {
            case CARRIAGES -> carriageMenus(dims);
            case CONTENTS -> contentsMenus(dims);
            case TRACKS -> trackMenus(dims);
            case ARCHITECTURE -> Collections.emptyList();
        };
    }

    private static List<EditorTypeMenusPacket.Menu> carriageMenus(CarriageDims dims) {
        List<EditorTypeMenusPacket.Menu> out = new ArrayList<>();
        List<EditorTypeMenusPacket.CategoryButton> categoryBar = buildCategoryBar();
        List<EditorTypeMenusPacket.TypeTab> typeStrip = buildCarriagesTypeStrip();
        String activeId = EditorCategory.CARRIAGES.id();

        // Carriages row (extends along +X). First plot is the first variant
        // in the registry's ordering — same as EditorPlotLabels.carriageLabels.
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (!variants.isEmpty()) {
            CarriageVariant first = variants.get(0);
            BlockPos firstOrigin = CarriageEditor.plotOrigin(first, dims);
            if (firstOrigin != null) {
                Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
                BlockPos anchor = anchorForXRow(firstOrigin, footprint);
                String cat = EditorCategory.CARRIAGES.name();
                CarriageWeights weights = CarriageWeights.current();
                List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(variants.size());
                for (CarriageVariant v : variants) {
                    EditorPlotLabels.Provenance p = EditorPlotLabels.provenanceOf(
                        CarriageTemplateStore.fileForId(v.id()));
                    TemplateGate g = weights.gateFor(v.id());
                    String stageId = weights.stageIdFor(v.id());
                    rows.add(new EditorTypeMenusPacket.Variant(
                        v.id(), weights.weightFor(v.id()),
                        g.minLevel(), g.maxLevel(), TrainPhase.toMask(g.phases()),
                        cat, v.id(), v.id(), p.isUser(), p.isImported(),
                        stageId == null ? "" : stageId));
                }
                out.add(new EditorTypeMenusPacket.Menu(
                    anchor, "Carriages", rows, false,
                    activeId, categoryBar, typeStrip));
            }
        }

        // Parts kind rows (CARRIAGES view stamps these alongside the carriage row).
        addPartMenu(out, CarriagePartKind.FLOOR, "Floor", dims, activeId, categoryBar, typeStrip);
        addPartMenu(out, CarriagePartKind.WALLS, "Walls", dims, activeId, categoryBar, typeStrip);
        addPartMenu(out, CarriagePartKind.ROOF, "Roof", dims, activeId, categoryBar, typeStrip);
        addPartMenu(out, CarriagePartKind.DOORS, "Doors", dims, activeId, categoryBar, typeStrip);

        return out;
    }

    private static void addPartMenu(
        List<EditorTypeMenusPacket.Menu> out, CarriagePartKind kind, String typeName, CarriageDims dims,
        String activeId,
        List<EditorTypeMenusPacket.CategoryButton> categoryBar,
        List<EditorTypeMenusPacket.TypeTab> typeStrip
    ) {
        List<String> names = CarriagePartRegistry.registeredNames(kind);
        if (names.isEmpty()) return;
        // Anchor on the row's fixed slot-0 origin, NOT the first part's plotOrigin — the latter is
        // null when that part is hidden by the visibility filter, which would drop the whole list.
        BlockPos firstOrigin = CarriagePartEditor.rowOrigin(kind, dims);
        if (firstOrigin == null) return;
        Vec3i footprint = kind.dims(dims);
        BlockPos anchor = anchorForXRow(firstOrigin, footprint);
        // Parts have no weight pool — every variant row gets NO_WEIGHT so the
        // renderer omits the weight cell and lets the name fill the row.
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(names.size());
        for (String name : names) {
            EditorPlotLabels.Provenance p = EditorPlotLabels.provenanceOf(
                CarriagePartTemplateStore.fileFor(kind, name));
            rows.add(new EditorTypeMenusPacket.Variant(
                name, EditorPlotLabelsPacket.NO_WEIGHT, "PARTS", kind.id(), name,
                p.isUser(), p.isImported()));
        }
        out.add(new EditorTypeMenusPacket.Menu(
            anchor, typeName, rows, false,
            activeId, categoryBar, typeStrip));
    }

    private static List<EditorTypeMenusPacket.Menu> contentsMenus(CarriageDims dims) {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        if (all.isEmpty()) return Collections.emptyList();
        // Sub-variants live in their parent's +Z column, not in the top-level
        // +X row. Filter them out of this menu — the per-plot sub-variants
        // companion (see VariantOverlayRenderer.appendSubVariantsCompanion)
        // lists them for the variant the player is editing.
        java.util.Set<String> children = games.brennan.dungeontrain.editor.CarriageContentsGroupStore.allChildIds();
        List<CarriageContents> topLevel = new ArrayList<>(all.size());
        for (CarriageContents c : all) {
            if (children.contains(c.id())) continue;
            topLevel.add(c);
        }
        if (topLevel.isEmpty()) return Collections.emptyList();
        CarriageContents first = topLevel.get(0);
        BlockPos firstOrigin = CarriageContentsEditor.plotOrigin(first, dims);
        if (firstOrigin == null) return Collections.emptyList();
        Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
        BlockPos anchor = anchorForXRow(firstOrigin, footprint);
        String cat = EditorCategory.CONTENTS.name();
        CarriageContentsWeights weights = CarriageContentsWeights.current();
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(topLevel.size());
        for (CarriageContents c : topLevel) {
            EditorPlotLabels.Provenance p = EditorPlotLabels.provenanceOf(
                CarriageContentsStore.fileForId(c.id()));
            TemplateGate g = weights.gateFor(c.id());
            String stageId = weights.stageIdFor(c.id());
            rows.add(new EditorTypeMenusPacket.Variant(
                c.id(), weights.weightFor(c.id()),
                g.minLevel(), g.maxLevel(), TrainPhase.toMask(g.phases()),
                cat, c.id(), c.id(), p.isUser(), p.isImported(),
                subVariantsFor(c.id(), cat), stageId == null ? "" : stageId));
        }
        List<EditorTypeMenusPacket.CategoryButton> categoryBar = buildCategoryBar();
        List<EditorTypeMenusPacket.TypeTab> typeStrip = List.of(
            new EditorTypeMenusPacket.TypeTab("Contents", cat, first.id(), first.id()));
        return List.of(new EditorTypeMenusPacket.Menu(
            anchor, "Contents", rows, false,
            EditorCategory.CONTENTS.id(), categoryBar, typeStrip));
    }

    private static List<EditorTypeMenusPacket.Menu> trackMenus(CarriageDims dims) {
        List<EditorTypeMenusPacket.Menu> out = new ArrayList<>();
        List<EditorTypeMenusPacket.CategoryButton> categoryBar = buildCategoryBar();
        List<EditorTypeMenusPacket.TypeTab> typeStrip = buildTracksTypeStrip();
        String activeId = EditorCategory.TRACKS.id();

        addTrackKindMenu(out, TrackKind.TILE, "Track", dims, activeId, categoryBar, typeStrip);
        for (PillarSection s : PillarSection.values()) {
            addTrackKindMenu(out, PillarTemplateStore.pillarKind(s),
                "Pillar " + capitalise(s.id()), dims, activeId, categoryBar, typeStrip);
        }
        for (PillarAdjunct a : PillarAdjunct.values()) {
            addTrackKindMenu(out, PillarTemplateStore.adjunctKind(a),
                capitalise(a.id()), dims, activeId, categoryBar, typeStrip);
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            addTrackKindMenu(out, TunnelTemplateStore.tunnelKind(v),
                "Tunnel " + capitalise(v.name().toLowerCase(Locale.ROOT)), dims,
                activeId, categoryBar, typeStrip);
        }
        return out;
    }

    private static void addTrackKindMenu(
        List<EditorTypeMenusPacket.Menu> out, TrackKind kind, String typeName, CarriageDims dims,
        String activeId,
        List<EditorTypeMenusPacket.CategoryButton> categoryBar,
        List<EditorTypeMenusPacket.TypeTab> typeStrip
    ) {
        List<String> names = TrackVariantRegistry.namesFor(kind);
        if (names.isEmpty()) return;
        BlockPos firstOrigin = TrackSidePlots.plotOrigin(kind, names.get(0), dims);
        Vec3i footprint = TrackSidePlots.footprint(kind, dims);
        BlockPos anchor = anchorForZRow(firstOrigin, footprint);
        String cat = EditorCategory.TRACKS.name();
        String modelId = kind.id();
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(names.size());
        for (String name : names) {
            EditorPlotLabels.Provenance p = EditorPlotLabels.provenanceOf(
                games.brennan.dungeontrain.track.variant.TrackVariantStore.fileFor(kind, name));
            TemplateGate g = TrackVariantWeights.gateFor(kind, name);
            String stageId = TrackVariantWeights.stageIdFor(kind, name);
            rows.add(new EditorTypeMenusPacket.Variant(
                name, TrackVariantWeights.weightFor(kind, name),
                g.minLevel(), g.maxLevel(), TrainPhase.toMask(g.phases()),
                cat, modelId, name, p.isUser(), p.isImported(),
                stageId == null ? "" : stageId));
        }
        out.add(new EditorTypeMenusPacket.Menu(
            anchor, typeName, rows, false,
            activeId, categoryBar, typeStrip));
    }

    // ---------- nav chrome builders ----------

    /**
     * Every category button shown on the top bar. Order matches the enum
     * declaration so all anchors render the same sequence — Carriages,
     * Contents, Tracks. ARCHITECTURE is intentionally excluded — it has no
     * stamped plots and clicking the button lands the player nowhere useful,
     * so we hide it from the bar until the category actually has content.
     */
    private static List<EditorTypeMenusPacket.CategoryButton> buildCategoryBar() {
        List<EditorTypeMenusPacket.CategoryButton> bar = new ArrayList<>(EditorCategory.values().length);
        for (EditorCategory c : EditorCategory.values()) {
            if (c == EditorCategory.ARCHITECTURE) continue;
            bar.add(new EditorTypeMenusPacket.CategoryButton(c.id(), c.displayName()));
        }
        return bar;
    }

    /**
     * Type tabs for the CARRIAGES category. Mirrors the menu builder
     * iteration order so the visual tab index lines up with the row order
     * in the world. Types with no registered variants are skipped — a tab
     * with no teleport target would be a dead click.
     */
    private static List<EditorTypeMenusPacket.TypeTab> buildCarriagesTypeStrip() {
        List<EditorTypeMenusPacket.TypeTab> strip = new ArrayList<>();
        String carriagesCat = EditorCategory.CARRIAGES.name();
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (!variants.isEmpty()) {
            CarriageVariant first = variants.get(0);
            strip.add(new EditorTypeMenusPacket.TypeTab(
                "Carriages", carriagesCat, first.id(), first.id()));
        }
        addPartTab(strip, CarriagePartKind.FLOOR, "Floor");
        addPartTab(strip, CarriagePartKind.WALLS, "Walls");
        addPartTab(strip, CarriagePartKind.ROOF, "Roof");
        addPartTab(strip, CarriagePartKind.DOORS, "Doors");
        return strip;
    }

    private static void addPartTab(
        List<EditorTypeMenusPacket.TypeTab> strip, CarriagePartKind kind, String typeName
    ) {
        List<String> names = CarriagePartRegistry.registeredNames(kind);
        if (names.isEmpty()) return;
        strip.add(new EditorTypeMenusPacket.TypeTab(
            typeName, "PARTS", kind.id(), names.get(0)));
    }

    /**
     * Type tabs for the TRACKS category. Mirrors the
     * {@link #trackMenus(CarriageDims)} iteration order so the tab strip
     * sequence matches the world row layout (track tile, then pillars
     * bottom→top, then stairs, then tunnels).
     */
    private static List<EditorTypeMenusPacket.TypeTab> buildTracksTypeStrip() {
        List<EditorTypeMenusPacket.TypeTab> strip = new ArrayList<>();
        addTrackTab(strip, TrackKind.TILE, "Track");
        for (PillarSection s : PillarSection.values()) {
            addTrackTab(strip, PillarTemplateStore.pillarKind(s),
                "Pillar " + capitalise(s.id()));
        }
        for (PillarAdjunct a : PillarAdjunct.values()) {
            addTrackTab(strip, PillarTemplateStore.adjunctKind(a),
                capitalise(a.id()));
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            addTrackTab(strip, TunnelTemplateStore.tunnelKind(v),
                "Tunnel " + capitalise(v.name().toLowerCase(Locale.ROOT)));
        }
        return strip;
    }

    /**
     * Children of {@code parentId} as packet-shaped variants, ready to
     * inline as the horizontal sub-variant row next to a CONTENTS variant
     * in the nav menu. Empty when the contents id has no group definition
     * or the group has no members. Provenance tints flow through the same
     * fields as top-level variants.
     */
    private static List<EditorTypeMenusPacket.Variant> subVariantsFor(String parentId, String category) {
        java.util.Optional<games.brennan.dungeontrain.train.CarriageContentsGroup> group =
            games.brennan.dungeontrain.editor.CarriageContentsGroupStore.get(parentId);
        if (group.isEmpty()) return java.util.Collections.emptyList();
        List<games.brennan.dungeontrain.train.CarriageContentsGroup.Member> members = group.get().members();
        if (members.isEmpty()) return java.util.Collections.emptyList();
        List<EditorTypeMenusPacket.Variant> out = new ArrayList<>(members.size());
        for (games.brennan.dungeontrain.train.CarriageContentsGroup.Member m : members) {
            EditorPlotLabels.Provenance prov = EditorPlotLabels.provenanceOf(
                games.brennan.dungeontrain.editor.CarriageContentsStore.fileForId(m.id()));
            out.add(new EditorTypeMenusPacket.Variant(
                m.id(), m.weight(), category, m.id(), m.id(),
                prov.isUser(), prov.isImported()));
        }
        return out;
    }

    private static void addTrackTab(
        List<EditorTypeMenusPacket.TypeTab> strip, TrackKind kind, String typeName
    ) {
        List<String> names = TrackVariantRegistry.namesFor(kind);
        if (names.isEmpty()) return;
        strip.add(new EditorTypeMenusPacket.TypeTab(
            typeName, EditorCategory.TRACKS.name(), kind.id(), names.get(0)));
    }

    /**
     * Anchor for an X-extending row: one {@code GAP} past the first plot's
     * {@code -X} cage edge, lifted {@link #Y_ANCHOR_LIFT} blocks above the
     * cage top, centred over the footprint Z.
     */
    private static BlockPos anchorForXRow(BlockPos firstOrigin, Vec3i footprint) {
        return new BlockPos(
            firstOrigin.getX() - MENU_GAP,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() + footprint.getZ() / 2
        );
    }

    /**
     * Z offset from the carriages nav menu's centre — mirrors
     * {@code EditorHelpPanelRenderer.WORLD_OFFSET_BLOCKS} on the opposite
     * side, so the carriages nav menu sits between the help/welcome panel
     * ({@code +Z}) and the package menu ({@code -Z}). Same distance, opposite
     * side — visually balanced trio at the editor's entry door.
     */
    private static final int PACKAGE_MENU_Z_OFFSET = -5;

    /**
     * Anchor for the floating package menu — the worldspace mirror of the
     * X-menu's "Package" drilldown. Shares the carriages nav menu's
     * {@code -X} depth and {@code Y} lift so the two read as siblings on
     * the same horizontal plane, but offset on {@code +Z} so they appear
     * side-by-side rather than stacked along the player's view axis.
     *
     * <p>Returns {@code null} when no carriage variants are registered —
     * same fallthrough the carriages nav menu uses (no anchor without a
     * first plot).</p>
     */
    public static BlockPos packageMenuAnchor(CarriageDims dims) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (variants.isEmpty()) return null;
        BlockPos firstOrigin = CarriageEditor.plotOrigin(variants.get(0), dims);
        if (firstOrigin == null) return null;
        Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
        return new BlockPos(
            firstOrigin.getX() - MENU_GAP,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() + footprint.getZ() / 2 + PACKAGE_MENU_Z_OFFSET
        );
    }

    // ---------- Stages management panel ----------

    /** Category token on stage rows — the input handler routes their clicks to {@code editor stage …}. */
    public static final String STAGES_CATEGORY = "stages";

    /** Sentinel modelId for the synthetic "+ New Stage" row (name-cell click opens the name dialog). */
    public static final String STAGE_NEW_SENTINEL = "__new_stage__";

    /** Z offset for the Stages panel — one slot past the package menu, on the same {@code -Z} side. */
    private static final int STAGES_MENU_Z_OFFSET = -10;

    /** Anchor for the floating Stages panel — beside the carriages nav menu / package menu at the door. */
    public static BlockPos stagesMenuAnchor(CarriageDims dims) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (variants.isEmpty()) return null;
        BlockPos firstOrigin = CarriageEditor.plotOrigin(variants.get(0), dims);
        if (firstOrigin == null) return null;
        Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
        return new BlockPos(
            firstOrigin.getX() - MENU_GAP,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() + footprint.getZ() / 2 + STAGES_MENU_Z_OFFSET
        );
    }

    /**
     * X offset for the Stage Blocks panel — it now sits at the <b>same Z</b> as the Stages panel
     * ({@link #STAGES_MENU_Z_OFFSET}) but shifted this many blocks toward {@code +X}, so the two
     * read as a side-by-side pair at the door rather than stacked along {@code -Z}. Tunable; the
     * billboards face the player, so exact non-overlap is view-dependent — input handling is
     * double-dispatch-guarded regardless.
     */
    private static final int STAGE_PANEL_X_OFFSET = 6;

    /**
     * Anchor for the Stage Blocks panel (the "stage V menu") — the sibling billboard beside the
     * Stages panel: same Z, offset {@code +X}. Same fallthrough as {@link #stagesMenuAnchor}:
     * {@code null} when no carriage variants are registered.
     */
    public static BlockPos stagePanelAnchor(CarriageDims dims) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (variants.isEmpty()) return null;
        BlockPos firstOrigin = CarriageEditor.plotOrigin(variants.get(0), dims);
        if (firstOrigin == null) return null;
        Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
        return new BlockPos(
            firstOrigin.getX() - MENU_GAP + STAGE_PANEL_X_OFFSET,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() + footprint.getZ() / 2 + STAGES_MENU_Z_OFFSET
        );
    }

    /**
     * The Stages management panel: one gated row per Stage (name + min/max/dimension cells, edited via
     * the {@code editor stage …} commands) plus a synthetic "+ New Stage" row whose name-cell click
     * opens the name dialog. {@code null} when there is no anchor (no carriages registered yet).
     */
    public static EditorTypeMenusPacket.Menu buildStagesMenu(CarriageDims dims) {
        BlockPos anchor = stagesMenuAnchor(dims);
        if (anchor == null) return null;
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>();
        for (games.brennan.dungeontrain.template.Stage s : StageStore.allStages()) {
            TemplateGate g = s.gate();
            // weight = NO_WEIGHT keeps the row weightless; the gated ctor still carries the gate so
            // the row reads as (name + level/dimension) and the client edit screen can show values.
            rows.add(new EditorTypeMenusPacket.Variant(
                s.name(), EditorPlotLabelsPacket.NO_WEIGHT,
                g.minLevel(), g.maxLevel(), TrainPhase.toMask(g.phases()),
                STAGES_CATEGORY, s.id(), s.id(), true, false));
        }
        // The companion-menu renderer adds its own "+ New" footer row (routed to the StageNameScreen
        // by the input handler), so no synthetic create row is needed here.
        return new EditorTypeMenusPacket.Menu(
            anchor, "Stages", rows, false, "", List.of(), List.of(), false, true);
    }

    /**
     * Anchor for a Z-extending row: one {@code GAP} past the first plot's
     * {@code -Z} cage edge, lifted {@link #Y_ANCHOR_LIFT} blocks above the
     * cage top, centred over the footprint X.
     */
    private static BlockPos anchorForZRow(BlockPos firstOrigin, Vec3i footprint) {
        return new BlockPos(
            firstOrigin.getX() + footprint.getX() / 2,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() - MENU_GAP
        );
    }

    private static String capitalise(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
