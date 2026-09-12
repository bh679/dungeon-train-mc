package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The row of tag chips above the notes box: "All" plus one chip per tag that has at least one
 * entry in the notes being shown, each labelled with its count. Chips wrap onto further rows when
 * the strip is narrower than they are, and the bar reports its height so the screen can size the
 * notes box under it. Clicking a chip toggles it; "All" clears the selection. Selection is a set —
 * any chosen tag matches — kept in {@link VersionCompareState} so both views share it.
 *
 * <p>This is a layout helper, not a widget: it builds the buttons and hands them to the screen to
 * add, because a screen rebuild is what applies a filter change.</p>
 */
@OnlyIn(Dist.CLIENT)
final class TagFilterBar {

    static final int CHIP_H = 14;
    static final int CHIP_PAD = 8;
    static final int CHIP_GAP = 3;
    static final int ROW_GAP = 3;
    private static final String KEY_ALL = "gui.dungeontrain.version.compare.tag.all";
    private static final float SELECTED_R = 0.45F, SELECTED_G = 0.6F, SELECTED_B = 1.0F;

    private final List<AbstractWidget> chips = new ArrayList<>();
    private final int height;

    /**
     * Lays out the chips for {@code counts} inside the strip at ({@code x}, {@code y}) of width
     * {@code width}. {@code onChange} receives the new selection when a chip is clicked.
     */
    TagFilterBar(Font font, int x, int y, int width, Map<ChangelogTag, Integer> counts,
                 Set<ChangelogTag> selected, Consumer<Set<ChangelogTag>> onChange) {
        List<Chip> wanted = new ArrayList<>();
        wanted.add(new Chip(null, Component.translatable(KEY_ALL), selected.isEmpty()));
        for (ChangelogTag tag : ChangelogTag.values()) {
            int n = counts.getOrDefault(tag, 0);
            if (n == 0) continue;
            wanted.add(new Chip(tag, Component.empty().append(tag.label()).append(" (" + n + ")"),
                    selected.contains(tag)));
        }

        int cx = x;
        int cy = y;
        for (Chip chip : wanted) {
            int w = Math.min(width, font.width(chip.label()) + CHIP_PAD);
            if (cx + w > x + width && cx > x) {
                cx = x;
                cy += CHIP_H + ROW_GAP;
            }
            DarkTintedButton button = chip.selected()
                    ? new DarkTintedButton(cx, cy, w, CHIP_H, chip.label(), b -> onChange.accept(toggle(selected, chip.tag())),
                            SELECTED_R, SELECTED_G, SELECTED_B)
                    : new DarkTintedButton(cx, cy, w, CHIP_H, chip.label(), b -> onChange.accept(toggle(selected, chip.tag())));
            chips.add(button);
            cx += w + CHIP_GAP;
        }
        this.height = cy + CHIP_H - y;
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

    /** Total height of the chip rows, for the caller's layout. */
    int height() {
        return height;
    }

    private record Chip(ChangelogTag tag, Component label, boolean selected) {}
}
