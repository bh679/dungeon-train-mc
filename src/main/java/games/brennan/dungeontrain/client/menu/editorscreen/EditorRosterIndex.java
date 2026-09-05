package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An immutable, queryable view over one roster snapshot — the part of the browser that can be
 * tested without a client.
 *
 * <p>Pages map to categories one to one except Carriages, which also shows the four part kinds
 * as further type strips: that is how the world-space strip reads too, and a builder thinks of a
 * floor as part of a carriage rather than as its own category.</p>
 */
public final class EditorRosterIndex {

    /** Where a template came from, as the browser filters on it. */
    public enum Provenance { BUILTIN, USER, IMPORTED }

    /**
     * The browser's filter row: two independent toggles and, for a developer, one creator.
     *
     * <p>Toggles rather than a one-of-four choice, so "mine and the built-ins, but nothing
     * imported" is sayable — it was not before. Neither toggle set means no filtering at all,
     * which is why there is no longer an "All" to pick.</p>
     */
    /**
     * The filter row, one flag per {@link Provenance}.
     *
     * <p>Three flags rather than two because there are three kinds of template, and the axis that
     * used to be missing — content from an installed package — was therefore dropped by every state
     * except "nothing set": turning a chip on to widen the search narrowed it, and a player with a
     * package could not see any of it under the browser's own default.</p>
     */
    public record Filters(boolean mine, boolean builtin, boolean imported) {
        public static final Filters NONE = new Filters(false, false, false);

        /**
         * What the browser opens on: everything the author has, without the built-ins.
         *
         * <p>An editor is opened to work on your own things far more often than to look at what
         * shipped, and the built-ins outnumber them heavily in every category. A package you
         * installed counts as yours to browse — it is on this machine because you put it there.</p>
         */
        public static final Filters DEFAULT = new Filters(true, false, true);

        public Filters withMine(boolean on) {
            return new Filters(on, builtin, imported);
        }

        public Filters withBuiltin(boolean on) {
            return new Filters(mine, on, imported);
        }

        public Filters withImported(boolean on) {
            return new Filters(mine, builtin, on);
        }

        /**
         * Mine, carrying imported with it — for a surface that does not offer the imported chip.
         *
         * <p>The chip is a dev-mode affordance, so on every other build there is no way to ask for
         * package content by name. Slaving it to "mine" there is what keeps it reachable: without
         * this, turning Mine on would make somebody's installed package disappear with nothing on
         * screen to bring it back.</p>
         */
        public Filters withMineCarryingImported(boolean on) {
            return new Filters(on, builtin, on);
        }

        /** True when nothing is being filtered out. */
        public boolean isEmpty() {
            return !mine && !builtin && !imported;
        }

        boolean admits(Provenance p) {
            if (isEmpty()) return true;
            return switch (p) {
                case USER -> mine;
                case BUILTIN -> builtin;
                case IMPORTED -> imported;
            };
        }
    }

    /** One entry of a page's type strip. */
    public record TypeStrip(String typeName, String modelId, PlotCategory category, int count) {}

    /** A tile: the row, its key, and the group's self weight when it is a parent. */
    public record Tile(EditorTypeMenusPacket.Variant variant, VariantKey key, int selfWeight, int relayId) {
        /** A tile the relay has no row for — every sub-variant, and any template never uploaded. */
        public Tile(EditorTypeMenusPacket.Variant variant, VariantKey key, int selfWeight) {
            this(variant, key, selfWeight, 0);
        }
        public boolean isGroup() {
            return !variant.subVariants().isEmpty();
        }
    }

    public static final EditorRosterIndex EMPTY = new EditorRosterIndex(List.of(), "",
        EditorRosterPacket.TrainSize.UNKNOWN);

    private final List<EditorRosterPacket.Group> groups;
    private final String stampedCategoryId;
    private final EditorRosterPacket.TrainSize trainSize;

    public EditorRosterIndex(List<EditorRosterPacket.Group> groups, String stampedCategoryId,
                             EditorRosterPacket.TrainSize trainSize) {
        this.groups = List.copyOf(groups);
        this.stampedCategoryId = stampedCategoryId == null ? "" : stampedCategoryId;
        this.trainSize = trainSize == null ? EditorRosterPacket.TrainSize.UNKNOWN : trainSize;
    }

    /** The world's carriage footprint, shared by every carriage, part and track. */
    public EditorRosterPacket.TrainSize trainSize() {
        return trainSize;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** The category whose plots are stamped, or null outside the editor. */
    public PlotCategory stampedCategory() {
        return PlotCategory.fromId(stampedCategoryId).orElse(null);
    }

    /** The type strips a page shows, in order, with their tile counts. */
    public List<TypeStrip> typeStrips(PlotCategory page) {
        List<TypeStrip> out = new ArrayList<>();
        for (EditorRosterPacket.Group g : groups) {
            PlotCategory gc = PlotCategory.fromId(g.categoryId()).orElse(null);
            if (gc == null) continue;
            boolean onPage = gc == page || (page == PlotCategory.CARRIAGES && gc == PlotCategory.PARTS);
            if (!onPage) continue;
            out.add(new TypeStrip(g.typeName(), g.modelId(), gc, g.entries().size()));
        }
        return out;
    }

    /** The tiles of one type strip, unfiltered. Empty when the page has no such strip. */
    public List<Tile> tiles(PlotCategory page, String typeName) {
        for (EditorRosterPacket.Group g : groups) {
            PlotCategory gc = PlotCategory.fromId(g.categoryId()).orElse(null);
            if (gc == null || !g.typeName().equals(typeName)) continue;
            boolean onPage = gc == page || (page == PlotCategory.CARRIAGES && gc == PlotCategory.PARTS);
            if (!onPage) continue;
            List<Tile> out = new ArrayList<>(g.entries().size());
            for (EditorRosterPacket.Entry e : g.entries()) {
                out.add(new Tile(e.variant(), VariantKey.of(e.variant(), ""), e.selfWeight(), e.relayId()));
            }
            return out;
        }
        return List.of();
    }

    /**
     * Every template in the roster, in one list — what the All page shows.
     *
     * <p>No type strip goes with it: fifteen strips across four categories would not fit the row,
     * and the point of the page is the whole roster at once. The filter box and the chips are what
     * narrow it.</p>
     */
    public List<Tile> allTiles() {
        List<Tile> out = new ArrayList<>();
        for (EditorRosterPacket.Group g : groups) {
            for (EditorRosterPacket.Entry e : g.entries()) {
                out.add(new Tile(e.variant(), VariantKey.of(e.variant(), ""), e.selfWeight(), e.relayId()));
            }
        }
        return out;
    }

    /**
     * The template the author is standing in, moved to the front.
     *
     * <p>Every page does this, so wherever they are in the browser the build under their feet is
     * the one already in reach — which is what the old Current tab was for, without spending a tab
     * on it.</p>
     */
    /**
     * What the main grid shows, and whether its first tile is only there because the author is
     * standing in it.
     *
     * <p>The plot under their feet is never filtered away. A filter is a way of looking for
     * something, and the one build a person can act on with every position-resolved control —
     * Save, Reset, Clear, the room geometry — disappearing from the grid because they typed three
     * letters is how you end up saving the wrong plot. So it is put back at the front, and drawn
     * faded ({@code firstIsGhost}) to say it is not part of what was asked for.</p>
     *
     * <p>Only ever put back from {@code all}, this page and strip's own tiles: standing in a carriage
     * does not make it belong on the Tracks page.</p>
     */
    public static Shown standingFirst(List<Tile> filtered, List<Tile> all, VariantKey standing) {
        if (standing == null) return new Shown(filtered, false);
        for (Tile tile : filtered) {
            if (tile.key().sameTemplate(standing)) {
                return new Shown(standingFirst(filtered, standing), false);
            }
        }
        for (Tile tile : all) {
            if (!tile.key().sameTemplate(standing)) continue;
            List<Tile> out = new ArrayList<>(filtered.size() + 1);
            out.add(tile);
            out.addAll(filtered);
            return new Shown(List.copyOf(out), true);
        }
        return new Shown(filtered, false);
    }

    /** The tiles a grid draws, and whether the first of them is the faded standing-in one. */
    public record Shown(List<Tile> tiles, boolean firstIsGhost) {}

    public static List<Tile> standingFirst(List<Tile> tiles, VariantKey standing) {
        if (standing == null || tiles.size() < 2) return tiles;
        for (int i = 0; i < tiles.size(); i++) {
            if (!tiles.get(i).key().sameTemplate(standing)) continue;
            if (i == 0) return tiles;
            List<Tile> out = new ArrayList<>(tiles.size());
            out.add(tiles.get(i));
            for (int j = 0; j < tiles.size(); j++) {
                if (j != i) out.add(tiles.get(j));
            }
            return out;
        }
        return tiles;
    }

    /** The first type strip of a page, or null when the page is empty — the default strip. */
    public TypeStrip firstStrip(PlotCategory page) {
        List<TypeStrip> strips = typeStrips(page);
        return strips.isEmpty() ? null : strips.get(0);
    }

    /**
     * Tiles that pass the filter row. A group parent stays when any of its members passes, so the
     * parent of a hit never disappears; the filter is applied again to the members themselves by
     * {@link #subVariants}.
     */
    public static List<Tile> filter(List<Tile> tiles, Filters filters, String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Tile> out = new ArrayList<>();
        for (Tile t : tiles) {
            if (passes(t.variant(), filters, needle)) {
                out.add(t);
                continue;
            }
            for (EditorTypeMenusPacket.Variant sv : t.variant().subVariants()) {
                if (passes(sv, filters, needle)) {
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }


    /** The members of a group tile that pass the filter row, keyed under their parent. */
    public static List<Tile> subVariants(Tile parent, Filters filters, String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Tile> out = new ArrayList<>();
        String parentId = parent.key().displayName();
        for (EditorTypeMenusPacket.Variant sv : parent.variant().subVariants()) {
            if (!passes(sv, filters, needle)) continue;
            out.add(new Tile(sv, VariantKey.of(sv, parentId), 0));
        }
        return out;
    }

    private static boolean passes(EditorTypeMenusPacket.Variant v, Filters filters, String needle) {
        if (filters != null && !filters.admits(provenanceOf(v))) return false;
        return needle.isEmpty() || v.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Imported wins over user, as the world-space tints do; neither is a bundled built-in. */
    public static Provenance provenanceOf(EditorTypeMenusPacket.Variant v) {
        if (v.isImported()) return Provenance.IMPORTED;
        if (v.isUser()) return Provenance.USER;
        return Provenance.BUILTIN;
    }

    /**
     * Find the tile for a key anywhere in the roster — a top-level row, or a member of one — or
     * null. Used to land the browser on the plot the player stands in.
     */
    public Tile find(VariantKey key) {
        if (key == null) return null;
        for (EditorRosterPacket.Group g : groups) {
            for (EditorRosterPacket.Entry e : g.entries()) {
                EditorTypeMenusPacket.Variant v = e.variant();
                VariantKey top = VariantKey.of(v, "");
                if (top.sameTemplate(key)) {
                    return new Tile(v, top, e.selfWeight(), e.relayId());
                }
                for (EditorTypeMenusPacket.Variant sv : v.subVariants()) {
                    VariantKey member = VariantKey.of(sv, top.displayName());
                    if (member.sameTemplate(key)) {
                        return new Tile(sv, member, 0);
                    }
                }
            }
        }
        return null;
    }

    /** The group that holds {@code key}, or null: which page and type strip to show for it. */
    public EditorRosterPacket.Group groupOf(VariantKey key) {
        if (key == null) return null;
        for (EditorRosterPacket.Group g : groups) {
            for (EditorRosterPacket.Entry e : g.entries()) {
                EditorTypeMenusPacket.Variant v = e.variant();
                if (VariantKey.of(v, "").sameTemplate(key)) return g;
                for (EditorTypeMenusPacket.Variant sv : v.subVariants()) {
                    if (VariantKey.of(sv, "").sameTemplate(key)) return g;
                }
            }
        }
        return null;
    }

    /** The parent tile of a sub-variant key, or null when the key is top-level or unknown. */
    public Tile parentOf(VariantKey key) {
        if (key == null || !key.isSubVariant()) return null;
        for (EditorRosterPacket.Group g : groups) {
            for (EditorRosterPacket.Entry e : g.entries()) {
                VariantKey top = VariantKey.of(e.variant(), "");
                if (top.category() == key.category() && top.displayName().equals(key.parentId())) {
                    return new Tile(e.variant(), top, e.selfWeight(), e.relayId());
                }
            }
        }
        return null;
    }
}
