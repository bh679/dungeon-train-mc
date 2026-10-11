package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.compat.PaintingCatalog;
import games.brennan.dungeontrain.compat.PaintingCatalog.Picture;
import games.brennan.dungeontrain.editor.VariantPainting;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry + labels of the Z menu's Painting popup for one painting row — opened from the same pill
 * slot as Grow (a painting never grows), drawn with the Grow popup's {@link GrowthPopupLayout.Row}s.
 *
 * <p>Sections, top to bottom: <b>Painting</b> (Off | On — off places the captured block as before),
 * then while on <b>Size</b> ({@code − Any +}, stepping through every placeable size) and
 * <b>Picture</b> ({@code − Random +}, stepping through the pictures of that size). Picking a picture
 * under Any size adopts the picture's size; changing the size drops a picture that no longer fits.</p>
 */
final class PaintingPopupLayout {

    static final int SECTION_ON = 0;
    static final int SECTION_SIZE = 1;
    static final int SECTION_IMAGE = 2;

    /** Option index of a stepper's middle (value) button — shown, never an action. */
    static final int STEPPER_VALUE = GrowthPopupLayout.STEPPER_VALUE;

    /** Longest picture name shown in the stepper before it is cut short. */
    private static final int MAX_NAME = 11;

    private PaintingPopupLayout() {}

    /** The visible sections for {@code painting}, top to bottom, positioned like the Grow popup. */
    static List<GrowthPopupLayout.Row> rows(VariantPainting painting, double panelW, double halfH) {
        String on = MenuLang.t("block_variant.active_on");
        String off = MenuLang.t("block_variant.active_off");
        List<Object[]> spec = new ArrayList<>(3);
        spec.add(new Object[] {SECTION_ON, MenuLang.t("block_variant.painting"),
            new String[] {off, on}, painting.on() ? 1 : 0});
        if (painting.on()) {
            String size = painting.anySize() ? MenuLang.t("block_variant.painting_any") : painting.sizeLabel();
            spec.add(new Object[] {SECTION_SIZE, MenuLang.t("block_variant.painting_size"),
                new String[] {"−", size, "+"}, -1});
            String image = painting.image() == null
                ? MenuLang.t("block_variant.painting_random") : pictureName(painting.image());
            spec.add(new Object[] {SECTION_IMAGE, MenuLang.t("block_variant.painting_picture"),
                new String[] {"−", image, "+"}, -1});
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

    /**
     * The painting option after clicking {@code option} in {@code section}, starting from
     * {@code current}. {@code pictures} is the placeable catalogue; steppers wrap around, with
     * Any / Random as the slot before the first size / picture.
     */
    static VariantPainting apply(VariantPainting current, List<Picture> pictures, int section, int option) {
        if (section == SECTION_ON) {
            return option == 1 ? (current.on() ? current : VariantPainting.ANY) : VariantPainting.NONE;
        }
        if (option == STEPPER_VALUE) return current;
        int step = option == 0 ? -1 : 1;
        if (section == SECTION_SIZE) {
            List<int[]> sizes = PaintingCatalog.sizes(pictures);
            int at = -1;
            for (int i = 0; i < sizes.size(); i++) {
                if (!current.anySize() && sizes.get(i)[0] == current.width() && sizes.get(i)[1] == current.height()) at = i;
            }
            int next = wrap(at + step, sizes.size());
            VariantPainting sized = next < 0 ? current.withSize(0, 0) : current.withSize(sizes.get(next)[0], sizes.get(next)[1]);
            Picture fixed = current.image() == null ? null : find(pictures, current.image());
            // Stepping to Any goes back to Random: a fixed picture under Any would just snap the
            // size back to the picture's own (PaintingPass#normalise) and the stepper would stick.
            boolean keeps = fixed != null && !sized.anySize() && sized.sizeMatches(fixed.width(), fixed.height());
            return keeps ? sized : sized.withImage(null);
        }
        List<Picture> ofSize = new ArrayList<>();
        for (Picture p : pictures) if (current.sizeMatches(p.width(), p.height())) ofSize.add(p);
        int at = -1;
        for (int i = 0; i < ofSize.size(); i++) if (ofSize.get(i).id().equals(current.image())) at = i;
        int next = wrap(at + step, ofSize.size());
        if (next < 0) return current.withImage(null);
        Picture pick = ofSize.get(next);
        // Under Any size a fixed picture brings its own size, so the Size stepper reads true.
        return current.withSize(pick.width(), pick.height()).withImage(pick.id());
    }

    /** Index in {@code -1 .. count-1} ({@code -1} = the Any / Random slot), wrapping both ways. */
    static int wrap(int i, int count) {
        return Math.floorMod(i + 1, count + 1) - 1;
    }

    /** Row pill label: the key word when off, e.g. {@code 2×1} / {@code Any} when on. */
    static String shortLabel(VariantPainting painting) {
        if (!painting.on()) return MenuLang.t("block_variant.painting");
        return painting.anySize() ? MenuLang.t("block_variant.painting_any") : painting.sizeLabel();
    }

    /** The picture's path, cut short to fit a stepper button ({@code minecraft:burning_skull} → {@code burning_sk…}). */
    static String pictureName(ResourceLocation id) {
        String path = id.getPath();
        return path.length() <= MAX_NAME ? path : path.substring(0, MAX_NAME - 1) + "…";
    }

    private static Picture find(List<Picture> pictures, ResourceLocation id) {
        for (Picture p : pictures) if (p.id().equals(id)) return p;
        return null;
    }
}
