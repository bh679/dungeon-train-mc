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

    /** The browser's filter row. {@code COMMUNITY} is an imported package; {@code MINE} a user file. */
    public enum Filter {
        ALL, BUILTIN, MINE, COMMUNITY;

        public boolean admits(Provenance p) {
            return switch (this) {
                case ALL -> true;
                case BUILTIN -> p == Provenance.BUILTIN;
                case MINE -> p == Provenance.USER;
                case COMMUNITY -> p == Provenance.IMPORTED;
            };
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

    public static final EditorRosterIndex EMPTY = new EditorRosterIndex(List.of(), "");

    private final List<EditorRosterPacket.Group> groups;
    private final String stampedCategoryId;

    public EditorRosterIndex(List<EditorRosterPacket.Group> groups, String stampedCategoryId) {
        this.groups = List.copyOf(groups);
        this.stampedCategoryId = stampedCategoryId == null ? "" : stampedCategoryId;
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
    public static List<Tile> filter(List<Tile> tiles, Filter filter, String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Tile> out = new ArrayList<>();
        for (Tile t : tiles) {
            if (passes(t.variant(), filter, needle)) {
                out.add(t);
                continue;
            }
            for (EditorTypeMenusPacket.Variant sv : t.variant().subVariants()) {
                if (passes(sv, filter, needle)) {
                    out.add(t);
                    break;
                }
            }
        }
        return out;
    }

    /** The members of a group tile that pass the filter row, keyed under their parent. */
    public static List<Tile> subVariants(Tile parent, Filter filter, String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        List<Tile> out = new ArrayList<>();
        String parentId = parent.key().displayName();
        for (EditorTypeMenusPacket.Variant sv : parent.variant().subVariants()) {
            if (!passes(sv, filter, needle)) continue;
            out.add(new Tile(sv, VariantKey.of(sv, parentId), 0));
        }
        return out;
    }

    private static boolean passes(EditorTypeMenusPacket.Variant v, Filter filter, String needle) {
        if (filter != null && !filter.admits(provenanceOf(v))) return false;
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
