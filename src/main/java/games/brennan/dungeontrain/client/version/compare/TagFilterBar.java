package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One row of tag chips above the notes box: "All", then a chip per tag that has at least one entry
 * in the notes being shown, most to least, each labelled with its count. The row never grows: when the chips do
 * not fit, arrows at either end page through them, the way the fullscreen view pages its tabs.
 * "All" is pinned so clearing the filter is always one click. Clicking a chip toggles it; the
 * selection is a set — any chosen tag matches — kept in {@link VersionCompareState} so both views
 * share it.
 *
 * <p>This is a layout helper, not a widget: it builds the buttons and hands them to the screen to
 * add, because a screen rebuild is what applies a filter or page change.</p>
 */
@OnlyIn(Dist.CLIENT)
final class TagFilterBar {

    static final int CHIP_H = 14;
    static final int CHIP_PAD = 8;
    static final int CHIP_GAP = 3;
    static final int ARROW_W = 12;
    private static final String KEY_ALL = "gui.dungeontrain.version.compare.tag.all";
    private static final float SELECTED_R = 0.45F, SELECTED_G = 0.6F, SELECTED_B = 1.0F;

    private final List<AbstractWidget> chips = new ArrayList<>();
    private final int page;

    /**
     * Lays out the strip at ({@code x}, {@code y}) of width {@code width}, showing page
     * {@code page} of the chips (clamped). {@code onChange} receives the new selection when a chip
     * is clicked; {@code onPage} the new page when an arrow is.
     */
    TagFilterBar(Font font, int x, int y, int width, Map<ChangelogTag, Integer> counts,
                 Set<ChangelogTag> selected, int page, Consumer<Set<ChangelogTag>> onChange, IntConsumer onPage) {
        List<Chip> tags = new ArrayList<>();
        for (ChangelogTag tag : byCount(counts)) {
            int n = counts.get(tag);
            tags.add(new Chip(tag, Component.empty().append(tag.label()).append(" (" + n + ")"),
                    selected.contains(tag)));
        }
        Chip all = new Chip(null, Component.translatable(KEY_ALL), selected.isEmpty());
        int allW = chipWidth(font, all, width);
        int cx = x;
        chips.add(button(cx, y, allW, all, selected, onChange));
        cx += allW + CHIP_GAP;

        // Pages: greedily fill the remaining width, leaving room for the arrows once anything
        // has to spill. Pages are computed up front so the arrows know where the last one is.
        List<List<Chip>> pages = paginate(font, tags, width - (cx - x));
        this.page = pages.isEmpty() ? 0 : Math.max(0, Math.min(page, pages.size() - 1));
        boolean paged = pages.size() > 1;
        if (paged) {
            DarkTintedButton left = new DarkTintedButton(cx, y, ARROW_W, CHIP_H, Component.literal("<"),
                    b -> onPage.accept(this.page - 1));
            left.active = this.page > 0;
            chips.add(left);
            cx += ARROW_W + CHIP_GAP;
        }
        for (Chip chip : pages.isEmpty() ? List.<Chip>of() : pages.get(this.page)) {
            int w = chipWidth(font, chip, width);
            chips.add(button(cx, y, w, chip, selected, onChange));
            cx += w + CHIP_GAP;
        }
        if (paged) {
            DarkTintedButton right = new DarkTintedButton(x + width - ARROW_W, y, ARROW_W, CHIP_H,
                    Component.literal(">"), b -> onPage.accept(this.page + 1));
            right.active = this.page < pages.size() - 1;
            chips.add(right);
        }
    }

    /** Tags with at least one entry, most to least; ties keep the taxonomy's order. */
    static List<ChangelogTag> byCount(Map<ChangelogTag, Integer> counts) {
        return counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.<Map.Entry<ChangelogTag, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Split {@code tags} into pages that each fit {@code width}, reserving arrow room when paging. */
    private static List<List<Chip>> paginate(Font font, List<Chip> tags, int width) {
        List<List<Chip>> pages = new ArrayList<>();
        if (tags.isEmpty()) return pages;
        // First try: everything on one page with no arrows.
        if (rowWidth(font, tags, width) <= width) {
            pages.add(tags);
            return pages;
        }
        int usable = width - (ARROW_W + CHIP_GAP) * 2;
        List<Chip> current = new ArrayList<>();
        for (Chip chip : tags) {
            List<Chip> trial = new ArrayList<>(current);
            trial.add(chip);
            if (!current.isEmpty() && rowWidth(font, trial, usable) > usable) {
                pages.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(chip);
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return pages;
    }

    private static int rowWidth(Font font, List<Chip> chips, int max) {
        int w = 0;
        for (Chip c : chips) {
            w += chipWidth(font, c, max) + CHIP_GAP;
        }
        return Math.max(0, w - CHIP_GAP);
    }

    private static int chipWidth(Font font, Chip chip, int max) {
        return Math.min(max, font.width(chip.label()) + CHIP_PAD);
    }

    private static DarkTintedButton button(int x, int y, int w, Chip chip, Set<ChangelogTag> selected,
                                           Consumer<Set<ChangelogTag>> onChange) {
        return chip.selected()
                ? new DarkTintedButton(x, y, w, CHIP_H, chip.label(), b -> onChange.accept(toggle(selected, chip.tag())),
                        SELECTED_R, SELECTED_G, SELECTED_B)
                : new DarkTintedButton(x, y, w, CHIP_H, chip.label(), b -> onChange.accept(toggle(selected, chip.tag())));
    }

    /** A new selection with {@code tag} flipped; {@code null} (the All chip) clears it. */
    static Set<ChangelogTag> toggle(Set<ChangelogTag> selected, ChangelogTag tag) {
        if (tag == null) return Set.of();
        Set<ChangelogTag> next = selected.isEmpty() ? EnumSet.noneOf(ChangelogTag.class) : EnumSet.copyOf(selected);
        if (!next.remove(tag)) {
            next.add(tag);
        }
        return next.isEmpty() ? Set.of() : Set.copyOf(next);
    }

    List<AbstractWidget> chips() {
        return chips;
    }

    /** The page actually shown, after clamping. */
    int page() {
        return page;
    }

    /** The strip is always one row. */
    int height() {
        return CHIP_H;
    }

    private record Chip(ChangelogTag tag, Component label, boolean selected) {}
}
