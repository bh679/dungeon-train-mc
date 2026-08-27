package games.brennan.dungeontrain.editor;

import java.util.Locale;
import java.util.Optional;

/**
 * The editor's <em>addressable</em> category vocabulary: which family of plots a
 * label row, menu tab, or click refers to.
 *
 * <p>This is deliberately a different question from the one {@link EditorCategory}
 * answers. {@code EditorCategory} is the <em>stamping</em> vocabulary — which set of
 * plots is currently built into the world — and parts are not their own set: they live
 * inside the CARRIAGES set, folded in by {@code EditorCategory.carriageModels()} and
 * reported as {@code CARRIAGES} by {@code EditorCategory.locate()}. But parts <em>are</em>
 * first-class to address: they have their own plots, their own type tabs, their own
 * visibility toggle, and their own {@code editor part enter} command. So the UI needs a
 * vocabulary of {@code EditorCategory} plus {@code PARTS}, which is this enum.
 * {@link #owner()} is the bridge back.</p>
 *
 * <p>Before this type existed the same concept travelled as a raw {@code String} in three
 * different cases at once — {@code "CARRIAGES"} on the wire, {@code "carriages"} in
 * commands and keyboard menus, {@code "Carriages"} in the status HUD — compared with
 * {@code switch} and {@code equals} across roughly thirty files. {@link #fromId(String)}
 * accepts all three, so a value's case stops deciding whether it routes.</p>
 *
 * <p><b>{@code "stages"} is not a member and must never become one.</b>
 * {@code EditorTypeMenus} writes that lowercase sentinel into the otherwise-uppercase
 * {@code Variant.category} field to mark the Stages panel's rows, which are not plots at
 * all. It is filtered out ahead of any category logic by the {@code isStagesMenu()}
 * check in {@code EditorTypeMenuInputHandler}. {@code fromId("stages")} returns empty, so
 * a stage row that ever reached a category switch would fall out harmlessly rather than
 * routing somewhere wrong.</p>
 */
public enum PlotCategory {
    CARRIAGES(EditorCategory.CARRIAGES),
    CONTENTS(EditorCategory.CONTENTS),
    TRACKS(EditorCategory.TRACKS),
    PORTALS(EditorCategory.PORTALS),
    ARCHITECTURE(EditorCategory.ARCHITECTURE),
    /** Carriage parts — addressable in its own right, but stamped as part of {@link #CARRIAGES}. */
    PARTS(EditorCategory.CARRIAGES);

    private static final PlotCategory[] VALUES = values();

    private final EditorCategory owner;

    PlotCategory(EditorCategory owner) {
        this.owner = owner;
    }

    /**
     * The plot set this category is stamped as part of. Identity for everything except
     * {@link #PARTS}, which reports {@link EditorCategory#CARRIAGES} — matching where
     * {@code EditorCategory.locate()} puts a part plot.
     */
    public EditorCategory owner() {
        return owner;
    }

    /** Stable lower-case token, and the same string {@code /dt editor <token>} uses. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Human-readable label for the status HUD. Kept in step with {@link EditorCategory#displayName()}. */
    public String displayName() {
        return this == PARTS ? "Parts" : owner.displayName();
    }

    /**
     * Parse any of the three case conventions this concept has travelled in back to a
     * category. Lenient by design and never throws: {@code null}, blank, the
     * {@code "stages"} sentinel, and anything else unrecognised all yield empty, which
     * every call site treats as "no category, do nothing".
     */
    public static Optional<PlotCategory> fromId(String raw) {
        if (raw == null) return Optional.empty();
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return Optional.empty();
        for (PlotCategory c : VALUES) {
            if (c.id().equals(key)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Widen a stamping category to its addressable counterpart. Total — never yields {@link #PARTS}. */
    public static PlotCategory of(EditorCategory category) {
        return valueOf(category.name());
    }

    /**
     * Whether a plot in this category draws the Save / Reset / Clear row, and correspondingly
     * whether the server accepts an {@code EditorPlotActionPacket} for it.
     *
     * <p>False for {@link #PARTS}: the part commands are position-resolved
     * ({@code runPartSave} reads the plot the player stands in) rather than addressed by
     * {@code (modelId, modelName)} like the others, so a part has no action row to draw.
     * This predicate is the single fact both the renderer and the packet handler consult —
     * previously the renderer's allowlist was the only thing keeping a PARTS action away
     * from a handler that would have dropped it.</p>
     */
    public boolean hasActionRow() {
        return this != PARTS && this != ARCHITECTURE;
    }

    /** Whether templates here have a spawn-weight pool to bump. False for parts and architecture. */
    public boolean hasWeightPool() {
        return this != PARTS && this != ARCHITECTURE;
    }

    /** Whether templates here carry a spawn gate — min/max level, dimensions, stage link. */
    public boolean hasGate() {
        return this != PARTS && this != ARCHITECTURE;
    }

    /**
     * Whether a plot here is an author-sized room box — the dimension steppers, mode, copies,
     * sky, exits and doorwall rows. Portal rooms only; nothing else has walls to decide about.
     */
    public boolean hasRoomBox() {
        return this == PORTALS;
    }

    /** Whether rows here carry the leading per-plot visibility checkbox. Parts only. */
    public boolean hasVisibilityToggle() {
        return this == PARTS;
    }
}
