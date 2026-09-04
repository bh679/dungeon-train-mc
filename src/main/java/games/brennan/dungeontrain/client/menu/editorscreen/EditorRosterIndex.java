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
    public record Filters(boolean mine, boolean builtin) {
        public static final Filters NONE = new Filters(false, false);

        /**
         * What the browser opens on: the author's own builds, without the built-ins.
         *
         * <p>An editor is opened to work on your own things far more often than to look at what
         * shipped, and the built-ins outnumber them heavily in every category.</p>
         */
        public static final Filters DEFAULT = new Filters(true, false);

        public Filters withMine(boolean on) {
            return new Filters(on, builtin);
        }

        public Filters withBuiltin(boolean on) {
            return new Filters(mine, on);
        }

        /** True when nothing is being filtered out. */
        public boolean isEmpty() {
            return !mine && !builtin;
        }

        boolean admits(Provenance p) {
            if (!mine && !builtin) return true;
            return (mine && p == Provenance.USER) || (builtin && p == Provenance.BUILTIN);
        }
    }

    /** One entry of a page's type strip. */
    public record TypeStrip(String typeName, String modelId, PlotCategory category, int count) {}

    /** A tile: the row, its key, and the group's self weight when it is a parent. */
    public record Tile(EditorTypeMenusPacket.Variant variant, VariantKey key, int selfWeight) {
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
                out.add(new Tile(e.variant(), VariantKey.of(e.variant(), ""), e.selfWeight()));
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
                out.add(new Tile(e.variant(), VariantKey.of(e.variant(), ""), e.selfWeight()));
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
                    return new Tile(v, top, e.selfWeight());
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
                    return new Tile(e.variant(), top, e.selfWeight());
                }
            }
        }
        return null;
    }
}
