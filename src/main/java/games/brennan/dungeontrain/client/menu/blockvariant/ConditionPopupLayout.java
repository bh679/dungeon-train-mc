package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantCondition;
import games.brennan.dungeontrain.editor.VariantConditionEdits;
import games.brennan.dungeontrain.editor.VariantConditionSet;
import games.brennan.dungeontrain.editor.VariantConditions;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's If popup for one row, drawn and hit-tested through the Grow
 * popup's {@link GrowthPopupLayout.Row} shape so the renderer and raycaster share one layout.
 *
 * <p>Top to bottom: the row's <b>If list</b> — one chip per condition, a Level chip when the row's
 * band is set, and {@code +} to start another; <b>Match</b> ({@code All | Any}, AND or OR) once
 * there are two things to combine; the <b>tabs</b> ({@code Touch | Group | Kind | Level}) — the type
 * of the selected chip; that tab's body; then the actions.</p>
 *
 * <p>Touch and Kind bodies: the {@code N S E W U D} multi-select, then in Kind the {@code Solid Empty
 * Any block} checkboxes, otherwise the <b>block list</b> as a strip of icons — in Touch the blocks
 * added from the hand (click one to drop it), in Group (whose picker is {@code ◂ G3 ▸}) every block
 * the group can roll, each toggled on or off. Level: Min and Max number cells, the same band the
 * row's purple cells edit. Actions: <b>+ Held</b> (Touch only) and <b>Clear</b>, which drops the
 * selected condition (or resets the band on the Level tab).</p>
 */
final class ConditionPopupLayout {

    static final int SECTION_MODE = 0;
    static final int SECTION_TOUCHING = 1;
    static final int SECTION_GROUP = 2;
    static final int SECTION_BLOCKS = 3;
    static final int SECTION_ACTIONS = 4;
    static final int SECTION_KIND = 5;
    static final int SECTION_LIST = 6;
    static final int SECTION_MATCH = 7;
    static final int SECTION_LEVEL_MIN = 8;
    static final int SECTION_LEVEL_MAX = 9;

    static final int MODE_TOUCHING = 0;
    static final int MODE_GROUP = 1;
    static final int MODE_KIND = 2;
    static final int MODE_LEVEL = 3;
    static final int ACTION_ADD = 0;
    static final int GROUP_PREV = 0;

    /** {@link #listOptions} entries that are not a condition index. */
    static final int LIST_PENDING = -1;
    static final int LIST_LEVEL = -2;
    static final int LIST_ADD = -3;

    private static final String[] TOUCHING_LABELS = {"N", "S", "E", "W", "U", "D"};

    private ConditionPopupLayout() {}

    /** The row's If list. */
    static VariantConditionSet set(BlockVariantSyncPacket.Entry entry) {
        return VariantConditionSet.parse(entry.condition());
    }

    /** True when the row's Level band is narrower than every tier. */
    static boolean hasLevel(BlockVariantSyncPacket.Entry entry) {
        return entry.minDiff() != 0 || entry.maxDiff() >= 0;
    }

    /** A row gated by its band alone opens its popup on the Level tab. */
    static boolean opensOnLevel(BlockVariantSyncPacket.Entry entry) {
        return hasLevel(entry) && set(entry).isDefault();
    }

    /** The condition the tabs edit — a fresh one when the selection is past the end of the list. */
    static VariantCondition selected(BlockVariantSyncPacket.Entry entry) {
        return set(entry).get(BlockVariantMenu.condIndex());
    }

    /**
     * What each button of the {@link #SECTION_LIST} row is, in order: a condition index, then
     * {@link #LIST_PENDING} for the not-yet-added condition the tabs are editing, {@link #LIST_LEVEL}
     * when the band is set, and {@link #LIST_ADD} unless the list is full.
     */
    static List<Integer> listOptions(BlockVariantSyncPacket.Entry entry) {
        VariantConditionSet set = set(entry);
        List<Integer> out = new ArrayList<>(set.size() + 3);
        for (int i = 0; i < set.size(); i++) out.add(i);
        boolean level = BlockVariantMenu.condLevelTab();
        if (!level && BlockVariantMenu.condIndex() >= set.size()) out.add(LIST_PENDING);
        if (hasLevel(entry)) out.add(LIST_LEVEL);
        if (set.size() < VariantConditionSet.MAX_CONDITIONS) out.add(LIST_ADD);
        return out;
    }

    /** How many things the row's If list combines: gating conditions plus the band. */
    static int predicateCount(BlockVariantSyncPacket.Entry entry) {
        return set(entry).gating().size() + (hasLevel(entry) ? 1 : 0);
    }

    /**
     * The visible sections for one row's If popup, top to bottom, positioned like the Grow popup.
     * {@code entry} supplies the list and, in Group mode, the group's own blocks; the selection
     * (which chip, or the Level tab) comes from {@link BlockVariantMenu}.
     */
    static List<GrowthPopupLayout.Row> rows(BlockVariantSyncPacket.Entry entry, double panelW, double halfH) {
        VariantConditionSet set = set(entry);
        boolean level = BlockVariantMenu.condLevelTab();
        VariantCondition c = selected(entry);
        List<Object[]> spec = new ArrayList<>(7);

        List<Integer> options = listOptions(entry);
        String[] chips = new String[options.size()];
        for (int i = 0; i < chips.length; i++) chips[i] = chipLabel(entry, set, options.get(i));
        spec.add(new Object[] {SECTION_LIST, MenuLang.t("block_variant.condition"), chips, -1});
        if (predicateCount(entry) >= 2 || set.any()) {
            spec.add(new Object[] {SECTION_MATCH, MenuLang.t("block_variant.condition_match"), new String[] {
                MenuLang.t("block_variant.condition_all"), MenuLang.t("block_variant.condition_match_any")},
                set.any() ? 1 : 0});
        }
        spec.add(new Object[] {SECTION_MODE, "", new String[] {
            MenuLang.t("block_variant.condition_touching"), MenuLang.t("block_variant.condition_group"),
            MenuLang.t("block_variant.condition_kind"), MenuLang.t("block_variant.condition_level")},
            level ? MODE_LEVEL : mode(c)});

        if (level) {
            spec.add(new Object[] {SECTION_LEVEL_MIN, MenuLang.t("block_variant.grow_min"),
                new String[] {Integer.toString(entry.minDiff())}, -1});
            spec.add(new Object[] {SECTION_LEVEL_MAX, MenuLang.t("block_variant.grow_max"),
                new String[] {maxLabel(entry.maxDiff())}, -1});
            spec.add(new Object[] {SECTION_ACTIONS, "", new String[] {MenuLang.t("block_variant.condition_clear")}, -1});
        } else {
            addConditionBody(spec, entry, c);
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

    /** The Touch / Group / Kind body for the selected condition: picker, values, actions. */
    private static void addConditionBody(List<Object[]> spec, BlockVariantSyncPacket.Entry entry, VariantCondition c) {
        if (c.isGroupTarget()) {
            spec.add(new Object[] {SECTION_GROUP, "", new String[] {"◂", "G" + c.groupId(), "▸"}, -1});
        } else {
            // Multi-select: which sides are lit comes from isSelected, not this row's single index.
            spec.add(new Object[] {SECTION_TOUCHING, "", TOUCHING_LABELS.clone(), -1});
        }
        if (c.isKindMode()) {
            spec.add(new Object[] {SECTION_KIND, MenuLang.t("block_variant.condition_is"), new String[] {
                MenuLang.t("block_variant.condition_solid"), MenuLang.t("block_variant.condition_empty"),
                MenuLang.t("block_variant.condition_any")}, -1});
        } else {
            // One icon slot per block; the labels stay blank because the renderer draws the icons.
            int n = strip(entry).size();
            String[] slots = n == 0 ? new String[] {"–"} : new String[n];
            for (int i = 0; i < n; i++) slots[i] = "";
            spec.add(new Object[] {SECTION_BLOCKS, MenuLang.t("block_variant.condition_is"), slots, -1});
        }
        // Group and Kind modes pick from fixed options, so there is nothing to add from the hand.
        spec.add(new Object[] {SECTION_ACTIONS, "", c.isGroupTarget() || c.isKindMode()
            ? new String[] {MenuLang.t("block_variant.condition_clear")}
            : new String[] {MenuLang.t("block_variant.condition_add_held"), MenuLang.t("block_variant.condition_clear")}, -1});
    }

    /** One chip of the If list. */
    private static String chipLabel(BlockVariantSyncPacket.Entry entry, VariantConditionSet set, int option) {
        return switch (option) {
            case LIST_PENDING -> "–";
            case LIST_LEVEL -> levelLabel(entry);
            case LIST_ADD -> "+";
            default -> conditionLabel(set.get(option));
        };
    }

    /** A condition's chip: {@code –} until it has a value, e.g. {@code D:2} / {@code G3:1} after. */
    static String conditionLabel(VariantCondition c) {
        return c.isDefault() ? "–" : c.targetLabel() + ":" + c.blocks().size();
    }

    /** The Level chip: e.g. {@code Lv 2-all}. */
    static String levelLabel(BlockVariantSyncPacket.Entry entry) {
        return MenuLang.t("block_variant.condition_level_chip", entry.minDiff(), maxLabel(entry.maxDiff()));
    }

    private static String maxLabel(int maxDiff) {
        return maxDiff < 0 ? MenuLang.t("stages.all") : Integer.toString(maxDiff);
    }

    /**
     * The blocks the {@link #SECTION_BLOCKS} strip shows for the selected condition, in slot order:
     * its chosen values in Touch mode, every block the target group can roll in Group mode. Kind mode
     * has no strip.
     */
    static List<ResourceLocation> strip(BlockVariantSyncPacket.Entry entry) {
        VariantCondition c = selected(entry);
        if (!c.isGroupTarget()) return c.blockValues();
        String[] perCondition = entry.conditionGroupValues().split(";", -1);
        int at = BlockVariantMenu.condIndex();
        String values = at < perCondition.length ? perCondition[at] : "";
        List<ResourceLocation> out = new ArrayList<>();
        for (String raw : values.split(",")) {
            ResourceLocation id = raw.isBlank() ? null : ResourceLocation.tryParse(raw.trim());
            if (id != null) out.add(id);
        }
        return out;
    }

    /**
     * Whether button {@code i} of {@code row} draws lit. The selected chip, the sides (a
     * multi-select), the kind boxes and Group mode's switched-on blocks light by state; every other
     * row lights its single selected button.
     */
    static boolean isSelected(BlockVariantSyncPacket.Entry entry, GrowthPopupLayout.Row row, int i) {
        if (row.section() == SECTION_LIST) {
            List<Integer> options = listOptions(entry);
            if (i >= options.size()) return false;
            int option = options.get(i);
            boolean level = BlockVariantMenu.condLevelTab();
            if (option == LIST_LEVEL) return level;
            if (option == LIST_PENDING) return !level;
            return option >= 0 && !level && option == BlockVariantMenu.condIndex();
        }
        VariantCondition c = selected(entry);
        if (row.section() == SECTION_TOUCHING) return c.hasSide(touching(i));
        if (row.section() == SECTION_KIND) return c.blocks().contains(kind(i));
        if (row.section() == SECTION_BLOCKS && c.isGroupTarget()) {
            List<ResourceLocation> strip = strip(entry);
            return i < strip.size() && c.blocks().contains(strip.get(i));
        }
        return i == row.selected();
    }

    /** Which tab is lit for condition {@code c} (Level is the popup's own state, not the condition's). */
    static int mode(VariantCondition c) {
        if (c.isGroupTarget()) return MODE_GROUP;
        return c.isKindMode() ? MODE_KIND : MODE_TOUCHING;
    }

    /** The mode token a tab click sends to retype a condition; {@code null} for the Level tab. */
    static String modeToken(int mode) {
        return switch (mode) {
            case MODE_GROUP -> VariantConditionEdits.GROUP_NEXT;
            case MODE_KIND -> VariantConditionEdits.MODE_KIND;
            case MODE_TOUCHING -> VariantConditionEdits.MODE_TOUCHING;
            default -> null;
        };
    }

    /** The touching target a button in {@link #SECTION_TOUCHING} sets. */
    static Direction touching(int option) {
        return VariantConditions.TOUCHING[Math.max(0, Math.min(VariantConditions.TOUCHING.length - 1, option))];
    }

    /** The kind value a checkbox in {@link #SECTION_KIND} toggles. */
    static ResourceLocation kind(int option) {
        return VariantCondition.KINDS.get(Math.max(0, Math.min(VariantCondition.KINDS.size() - 1, option)));
    }

    /** The group-stepper token for a button in {@link #SECTION_GROUP}; the middle steps forward too. */
    static String groupToken(int option) {
        return option == GROUP_PREV ? VariantConditionEdits.GROUP_PREV : VariantConditionEdits.GROUP_NEXT;
    }

    /** Whether {@code section} is one of the Level tab's number cells. */
    static boolean isLevelNumber(int section) {
        return section == SECTION_LEVEL_MIN || section == SECTION_LEVEL_MAX;
    }

    /**
     * Row pill label: {@code If} when nothing gates; the one condition's chip when one does
     * ({@code D:2}, {@code Lv 2-all}); otherwise {@code All:3} / {@code Any:3}.
     */
    static String shortLabel(BlockVariantSyncPacket.Entry entry) {
        VariantConditionSet set = set(entry);
        int count = predicateCount(entry);
        if (count == 0) return MenuLang.t("block_variant.condition");
        if (count == 1) return hasLevel(entry) ? levelLabel(entry) : conditionLabel(set.gating().get(0));
        return MenuLang.t(set.any() ? "block_variant.condition_match_any" : "block_variant.condition_all")
            + ":" + count;
    }

    /** True when the row's If list gates it at all. */
    static boolean isSet(BlockVariantSyncPacket.Entry entry) {
        return predicateCount(entry) > 0;
    }
}
