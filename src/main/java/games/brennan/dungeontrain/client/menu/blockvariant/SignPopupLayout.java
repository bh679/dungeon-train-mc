package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.SignVariants;
import games.brennan.dungeontrain.editor.VariantSign;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Sign popup for one sign row — opened from the Grow pill slot
 * (a sign never grows), drawn with the Grow popup's {@link GrowthPopupLayout.Row}s.
 *
 * <ul>
 *   <li>A top row of three icons: 🔒 lock (every sign row of the cell copies this one), the
 *       honeycomb wax toggle and the book-and-quill that opens {@link SignTextScreen}.</li>
 *   <li><b>Mount</b>: {@code Wall | Standing | Hanging | Wall hang | Auto}, two a row — only the
 *       mounts the row's wood has a block for, and Auto only where the plot's placer runs the
 *       post-overlay pass.</li>
 *   <li><b>Text</b>: {@code Same in cell | Own} — hidden while locked, which shares it already.</li>
 * </ul>
 */
final class SignPopupLayout {

    static final int SECTION_MOUNT = 0;
    /**
     * Sections of the Mount rows after the first: row {@code k} is {@code MOUNT_SECTIONS[k]}. Two
     * buttons a row — "Standing" and "Hanging" side by side ran into each other.
     */
    static final int[] MOUNT_SECTIONS = {SECTION_MOUNT, 4, 5};
    static final int PER_ROW = 2;
    static final int SECTION_TEXT = 1;
    /** The icon row: {@link #TOGGLE_LOCK}, {@link #TOGGLE_WAX}, {@link #TOGGLE_EDIT}. */
    static final int SECTION_TOGGLES = 6;

    static final int TOGGLE_LOCK = 0;
    static final int TOGGLE_WAX = 1;
    static final int TOGGLE_EDIT = 2;
    /** The lock button's glyph; the other two are item icons drawn over blank buttons. */
    static final String LOCK_GLYPH = "\uD83D\uDD12";

    static final int TEXT_SHARED = 0;
    static final int TEXT_OWN = 1;

    /** A {@code selected} index no button has: lights nothing (see {@code PotPopupLayout}). */
    private static final int NONE_LIT = 99;

    private SignPopupLayout() {}

    /** The popup's rows for a sign row holding {@code state}, positioned like the Grow popup. */
    static List<GrowthPopupLayout.Row> rows(BlockState state, VariantSign sign, boolean waxed,
                                            double panelW, double halfH) {
        int[] wires = mountWires(state);
        String[] mountButtons = new String[wires.length];
        int mountSelected = -1;
        SignVariants.Mount current = SignVariants.mountOf(state);
        for (int i = 0; i < wires.length; i++) {
            mountButtons[i] = mountLabel(wires[i]);
            boolean lit = sign.auto() ? wires[i] == SignVariants.AUTO_WIRE
                : current != null && wires[i] == current.ordinal();
            if (lit) mountSelected = i;
        }
        List<Object[]> spec = new ArrayList<>(6);
        spec.add(new Object[] {SECTION_TOGGLES, "", new String[] {LOCK_GLYPH, "", ""}, NONE_LIT});
        for (int k = 0; k * PER_ROW < mountButtons.length && k < MOUNT_SECTIONS.length; k++) {
            int from = k * PER_ROW;
            int to = Math.min(mountButtons.length, from + PER_ROW);
            boolean litHere = mountSelected >= from && mountSelected < to;
            spec.add(new Object[] {MOUNT_SECTIONS[k], k == 0 ? MenuLang.t("block_variant.sign_mount") : "",
                java.util.Arrays.copyOfRange(mountButtons, from, to), litHere ? mountSelected - from : NONE_LIT});
        }
        if (!sign.locked()) {
            spec.add(new Object[] {SECTION_TEXT, MenuLang.t("block_variant.sign_text"),
                new String[] {MenuLang.t("block_variant.sign_shared"), MenuLang.t("block_variant.sign_own")},
                sign.shared() ? TEXT_SHARED : TEXT_OWN});
        }

        double left = Math.max(-panelW / 2.0 + GrowthPopupLayout.PAD, -GrowthPopupLayout.POPUP_WIDTH / 2.0);
        double base = halfH + 2 * GrowthPopupLayout.PAD;
        List<GrowthPopupLayout.Row> rows = new ArrayList<>(spec.size());
        for (int i = 0; i < spec.size(); i++) {
            Object[] s = spec.get(i);
            double bottom = base + (spec.size() - 1 - i) * GrowthPopupLayout.ROW_HEIGHT;
            rows.add(new GrowthPopupLayout.Row((int) s[0], (String) s[1], (String[]) s[2], (int) s[3],
                left, left + GrowthPopupLayout.POPUP_WIDTH, bottom, bottom + GrowthPopupLayout.ROW_HEIGHT));
        }
        return rows;
    }

    /** True when button {@code i} of {@code row} is lit: the row's pick, or an icon toggle that is on. */
    static boolean isSelected(VariantSign sign, boolean waxed, GrowthPopupLayout.Row row, int i) {
        if (row.section() != SECTION_TOGGLES) return i == row.selected();
        return switch (i) {
            case TOGGLE_LOCK -> sign.locked();
            case TOGGLE_WAX -> waxed;
            default -> false;
        };
    }

    /** Index into {@link #mountWires} of the Mount button at {@code option} in {@code section}, or -1. */
    static int mountButton(int section, int option) {
        for (int k = 0; k < MOUNT_SECTIONS.length; k++) {
            if (MOUNT_SECTIONS[k] == section) return k * PER_ROW + option;
        }
        return -1;
    }

    /** True when {@code section} is one of the Mount rows. */
    static boolean isMountSection(int section) {
        return mountButton(section, 0) >= 0;
    }

    /** {@code SET_SIGN_MOUNT} values for the Mount buttons, in order: the wood's mounts, then Auto. */
    static int[] mountWires(BlockState state) {
        List<Integer> out = new ArrayList<>();
        SignVariants.Mount current = SignVariants.mountOf(state);
        for (SignVariants.Mount m : SignVariants.Mount.values()) {
            if (m == current || SignVariants.sibling(state, m) != null) out.add(m.ordinal());
        }
        // Auto is picked by the placers' post-overlay pass — the same plots that offer Connect.
        if (BlockVariantMenu.connectSupported()) out.add(SignVariants.AUTO_WIRE);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String mountLabel(int wire) {
        if (wire == SignVariants.AUTO_WIRE) return MenuLang.t("block_variant.sign_auto");
        return switch (SignVariants.Mount.values()[wire]) {
            case WALL -> MenuLang.t("block_variant.sign_wall");
            case STANDING -> MenuLang.t("block_variant.sign_standing");
            case HANGING -> MenuLang.t("block_variant.sign_hanging");
            case WALL_HANGING -> MenuLang.t("block_variant.sign_wall_hanging");
        };
    }

    /** Row pill label: the key word by default, {@code Locked} / {@code Auto} / {@code Same} when set. */
    static String shortLabel(VariantSign sign) {
        if (sign.locked()) return MenuLang.t("block_variant.sign_locked");
        if (sign.auto()) return MenuLang.t("block_variant.sign_auto");
        if (sign.shared()) return MenuLang.t("block_variant.sign_same");
        return MenuLang.t("block_variant.sign");
    }
}
