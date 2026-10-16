package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantCondition;
import games.brennan.dungeontrain.editor.VariantConditionEdits;
import games.brennan.dungeontrain.editor.VariantConditions;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's If popup for one row, drawn and hit-tested through the Grow
 * popup's {@link GrowthPopupLayout.Row} shape so the renderer and raycaster share one layout.
 *
 * <p>Top to bottom: the <b>mode</b> ({@code Touching | Group}), that mode's picker ({@code N S E W U D}
 * or {@code ◂ G3 ▸}), the <b>block list</b> as a horizontal strip of icons (OR'd; click one to drop
 * it), then <b>+ Held</b> and <b>Clear</b>.</p>
 */
final class ConditionPopupLayout {

    static final int SECTION_MODE = 0;
    static final int SECTION_TOUCHING = 1;
    static final int SECTION_GROUP = 2;
    static final int SECTION_BLOCKS = 3;
    static final int SECTION_ACTIONS = 4;

    static final int MODE_TOUCHING = 0;
    static final int MODE_GROUP = 1;
    static final int ACTION_ADD = 0;
    static final int ACTION_CLEAR = 1;
    static final int GROUP_PREV = 0;

    private static final String[] TOUCHING_LABELS = {"N", "S", "E", "W", "U", "D"};

    private ConditionPopupLayout() {}

    /** The visible sections for the row's condition token, top to bottom, positioned like the Grow popup. */
    static List<GrowthPopupLayout.Row> rows(String token, double panelW, double halfH) {
        VariantCondition c = VariantCondition.parse(token);
        List<Object[]> spec = new ArrayList<>(4);
        spec.add(new Object[] {SECTION_MODE, MenuLang.t("block_variant.condition"),
            new String[] {MenuLang.t("block_variant.condition_touching"), MenuLang.t("block_variant.condition_group")},
            c.isGroupTarget() ? MODE_GROUP : MODE_TOUCHING});
        if (c.isGroupTarget()) {
            spec.add(new Object[] {SECTION_GROUP, "", new String[] {"◂", "G" + c.groupId(), "▸"}, -1});
        } else {
            spec.add(new Object[] {SECTION_TOUCHING, "", TOUCHING_LABELS.clone(), touchingIndex(c.direction())});
        }
        // One icon slot per value; the labels stay blank because the renderer draws the icons.
        String[] slots = c.blocks().isEmpty() ? new String[] {"–"} : new String[c.blocks().size()];
        for (int i = 0; i < c.blocks().size(); i++) slots[i] = "";
        spec.add(new Object[] {SECTION_BLOCKS, MenuLang.t("block_variant.condition_is"), slots, -1});
        spec.add(new Object[] {SECTION_ACTIONS, "",
            new String[] {MenuLang.t("block_variant.condition_add_held"), MenuLang.t("block_variant.condition_clear")}, -1});

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

    /** The block ids shown in the {@link #SECTION_BLOCKS} strip, in slot order. */
    static List<ResourceLocation> blocks(String token) {
        return VariantCondition.parse(token).blocks();
    }

    /** True when the row's condition targets a group (Group mode). */
    static boolean isGroupMode(String token) {
        return VariantCondition.parse(token).isGroupTarget();
    }

    /** The touching target a button in {@link #SECTION_TOUCHING} sets. */
    static Direction touching(int option) {
        return VariantConditions.TOUCHING[Math.max(0, Math.min(VariantConditions.TOUCHING.length - 1, option))];
    }

    /** The group-stepper token for a button in {@link #SECTION_GROUP}; the middle steps forward too. */
    static String groupToken(int option) {
        return option == GROUP_PREV ? VariantConditionEdits.GROUP_PREV : VariantConditionEdits.GROUP_NEXT;
    }

    /** Row pill label: {@code If} when unset, e.g. {@code D:2} / {@code G3:1} when set. */
    static String shortLabel(String token) {
        VariantCondition c = VariantCondition.parse(token);
        if (c.isDefault()) return MenuLang.t("block_variant.condition");
        return c.targetLabel() + ":" + c.blocks().size();
    }

    static boolean isSet(String token) {
        return !VariantCondition.parse(token).isDefault();
    }

    private static int touchingIndex(Direction d) {
        for (int i = 0; i < VariantConditions.TOUCHING.length; i++) {
            if (VariantConditions.TOUCHING[i] == d) return i;
        }
        return -1;
    }
}
