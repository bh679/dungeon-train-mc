package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.FlowerPots;
import games.brennan.dungeontrain.editor.VariantPot;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Plant popup for one flower pot row — opened from the same pill
 * slot as Grow (a pot never grows), drawn with the Grow popup's {@link GrowthPopupLayout.Row}s.
 *
 * <p>Top to bottom: <b>Plant</b> ({@code As is | Pick} — as is places the captured pot), then while
 * picking the <b>chosen plants</b> ({@value #PER_ROW} to a row, click one to drop it; none listed
 * reads <i>Any plant</i>, the Random roll), a <b>browse</b> stepper {@code ◂ poppy ▸ Add} over every
 * plant a pot accepts, and {@code + Held | Random} (add the plant in hand / drop the list).</p>
 */
final class PotPopupLayout {

    static final int SECTION_ON = 0;
    static final int SECTION_BROWSE = 1;
    static final int SECTION_ACTIONS = 2;
    /** First chosen-plants row; row {@code k} is section {@code SECTION_LIST + k}. */
    static final int SECTION_LIST = 10;

    static final int BROWSE_PREV = 0;
    static final int BROWSE_NAME = 1;
    static final int BROWSE_NEXT = 2;
    static final int BROWSE_ADD = 3;
    static final int ACTION_HELD = 0;
    static final int ACTION_RANDOM = 1;

    /** Plant buttons on one chosen-plants row. */
    static final int PER_ROW = 4;

    /**
     * A {@code selected} index no button has: lights nothing, and (being ≥ 0) keeps the renderer
     * from reading a three-button row as a {@code − n +} stepper.
     */
    private static final int NONE_LIT = 99;

    /** Longest plant name shown on a button before it is cut short. */
    private static final int MAX_NAME = 10;

    /** Where the browse stepper points into {@link FlowerPots#allPlants} — client-side only. */
    private static int browse;

    private PotPopupLayout() {}

    /** The visible sections for {@code pot}, top to bottom, positioned like the Grow popup. */
    static List<GrowthPopupLayout.Row> rows(VariantPot pot, double panelW, double halfH) {
        List<Object[]> spec = new ArrayList<>();
        spec.add(new Object[] {SECTION_ON, MenuLang.t("block_variant.pot"),
            new String[] {MenuLang.t("block_variant.as_placed"), MenuLang.t("block_variant.pot_pick")},
            pot.on() ? 1 : 0});
        if (pot.on()) {
            if (pot.plants().isEmpty()) {
                spec.add(new Object[] {SECTION_LIST, MenuLang.t("block_variant.pot_plants"),
                    new String[] {MenuLang.t("block_variant.pot_any")}, 0});
            } else {
                for (int k = 0; k * PER_ROW < pot.plants().size(); k++) {
                    List<ResourceLocation> slice = pot.plants().subList(k * PER_ROW,
                        Math.min(pot.plants().size(), (k + 1) * PER_ROW));
                    String[] names = new String[slice.size()];
                    for (int i = 0; i < names.length; i++) names[i] = plantName(slice.get(i));
                    spec.add(new Object[] {SECTION_LIST + k, k == 0 ? MenuLang.t("block_variant.pot_plants") : "",
                        names, NONE_LIT});
                }
            }
            ResourceLocation shown = browsed();
            spec.add(new Object[] {SECTION_BROWSE, "",
                new String[] {"◂", shown == null ? "—" : plantName(shown), "▸", MenuLang.t("block_variant.pot_add")},
                NONE_LIT});
            spec.add(new Object[] {SECTION_ACTIONS, "",
                new String[] {MenuLang.t("block_variant.pot_held"), MenuLang.t("block_variant.painting_random")},
                pot.isRandom() ? ACTION_RANDOM : NONE_LIT});
        }

        double left = Math.max(-panelW / 2.0 + GrowthPopupLayout.PAD, -GrowthPopupLayout.POPUP_WIDTH / 2.0);
        List<GrowthPopupLayout.Row> rows = new ArrayList<>(spec.size());
        double base = halfH + 2 * GrowthPopupLayout.PAD;
        for (int i = 0; i < spec.size(); i++) {
            Object[] s = spec.get(i);
            int fromBottom = spec.size() - 1 - i;
            double bottom = base + fromBottom * GrowthPopupLayout.ROW_HEIGHT;
            rows.add(new GrowthPopupLayout.Row((int) s[0], (String) s[1], (String[]) s[2], (int) s[3],
                left, left + GrowthPopupLayout.POPUP_WIDTH, bottom, bottom + GrowthPopupLayout.ROW_HEIGHT));
        }
        return rows;
    }

    /** The chosen plant a click on list row {@code section}, button {@code option} names, or {@code null}. */
    @Nullable
    static ResourceLocation listedPlant(VariantPot pot, int section, int option) {
        if (section < SECTION_LIST || pot.plants().isEmpty()) return null;
        int at = (section - SECTION_LIST) * PER_ROW + option;
        return at < pot.plants().size() ? pot.plants().get(at) : null;
    }

    /** Step the browse stepper by {@code step}, wrapping round the pottable plants. */
    static void stepBrowse(int step) {
        int count = FlowerPots.allPlants().size();
        if (count > 0) browse = Math.floorMod(browse + step, count);
    }

    /** The plant the browse stepper shows, or {@code null} when nothing is pottable. */
    @Nullable
    static ResourceLocation browsed() {
        List<ResourceLocation> all = FlowerPots.allPlants();
        if (all.isEmpty()) return null;
        return all.get(Math.floorMod(browse, all.size()));
    }

    /** Row pill label: the key word when as is, {@code Random} or the plant count when picking. */
    static String shortLabel(VariantPot pot) {
        if (!pot.on()) return MenuLang.t("block_variant.pot");
        if (pot.isRandom()) return MenuLang.t("block_variant.painting_random");
        return pot.plants().size() == 1 ? plantName(pot.plants().get(0)) : "×" + pot.plants().size();
    }

    /** The plant's path, cut short to fit a button ({@code minecraft:oxeye_daisy} → {@code oxeye_dai…}). */
    static String plantName(ResourceLocation id) {
        String path = id.getPath();
        return path.length() <= MAX_NAME ? path : path.substring(0, MAX_NAME - 1) + "…";
    }
}
