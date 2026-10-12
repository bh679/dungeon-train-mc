package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.compat.PaintingCatalog.Picture;
import games.brennan.dungeontrain.editor.VariantPainting;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link PaintingPopupLayout#apply} — what each Painting popup button does to a row's option. */
final class PaintingPopupLayoutTest {

    private static final int MINUS = 0;
    private static final int PLUS = 2;

    private static Picture pic(String name, int w, int h) {
        return new Picture(ResourceLocation.withDefaultNamespace(name), w, h);
    }

    // Catalogue order is by id, as PaintingCatalog.placeable returns it.
    private static final List<Picture> PICTURES = List.of(
        pic("alban", 1, 1), pic("aztec", 1, 1), pic("courbet", 2, 1), pic("sea", 2, 1), pic("skeleton", 4, 3));

    @Test
    @DisplayName("On starts at Any / Random; Off clears the option")
    void onOff() {
        assertEquals(VariantPainting.ANY,
            PaintingPopupLayout.apply(VariantPainting.NONE, PICTURES, PaintingPopupLayout.SECTION_ON, 1));
        assertSame(VariantPainting.NONE,
            PaintingPopupLayout.apply(VariantPainting.of(2, 1, null), PICTURES, PaintingPopupLayout.SECTION_ON, 0));
    }

    @Test
    @DisplayName("Size steps Any → 1×1 → 2×1 → 4×3 → Any, and back")
    void sizeSteps() {
        VariantPainting p = VariantPainting.ANY;
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_SIZE, PLUS);
        assertEquals("1×1", p.sizeLabel());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_SIZE, PLUS);
        assertEquals("2×1", p.sizeLabel());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_SIZE, PLUS);
        assertEquals("4×3", p.sizeLabel());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_SIZE, PLUS);
        assertEquals(VariantPainting.ANY, p);
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_SIZE, MINUS);
        assertEquals("4×3", p.sizeLabel());
    }

    @Test
    @DisplayName("Picture steps through the size's pictures; Random sits before the first")
    void pictureSteps() {
        VariantPainting p = VariantPainting.of(2, 1, null);
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_IMAGE, PLUS);
        assertEquals("courbet", p.image().getPath());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_IMAGE, PLUS);
        assertEquals("sea", p.image().getPath());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_IMAGE, PLUS);
        assertNull(p.image());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_IMAGE, MINUS);
        assertEquals("sea", p.image().getPath());
    }

    @Test
    @DisplayName("a picture picked under Any adopts its size; a size change drops a picture that no longer fits")
    void sizeAndPictureStayConsistent() {
        VariantPainting p = PaintingPopupLayout.apply(VariantPainting.ANY, PICTURES,
            PaintingPopupLayout.SECTION_IMAGE, MINUS);
        assertEquals("skeleton", p.image().getPath());
        assertEquals("4×3", p.sizeLabel());
        p = PaintingPopupLayout.apply(p, PICTURES, PaintingPopupLayout.SECTION_SIZE, PLUS);
        assertEquals(VariantPainting.ANY, p);
    }

    @Test
    @DisplayName("long picture names are cut short to fit the stepper")
    void pictureName() {
        assertEquals("kebab", PaintingPopupLayout.pictureName(ResourceLocation.withDefaultNamespace("kebab")));
        assertEquals("burning_sk…", PaintingPopupLayout.pictureName(ResourceLocation.withDefaultNamespace("burning_skull")));
    }
}
