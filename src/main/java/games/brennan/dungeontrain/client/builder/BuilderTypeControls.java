package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The "what kind of thing is this?" controls, shared by the Train Builder screens that ask it.
 *
 * <p><b>New</b>, <b>Save-as</b> and <b>Open</b> all open with the same question — which mode, which
 * sub type, and for parts which kind of part — and a builder moving between them should be looking
 * at the same three controls in the same order, not at three arrangements that happen to agree.
 * They used to agree by copy; this is the one implementation they now agree by.</p>
 *
 * <p>What each screen does <em>below</em> these controls is its own business: New adds the name
 * field and the "copy from" picker, Open adds the template grid. Only the shared head of the screen
 * lives here.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTypeControls {

    /** Tall enough for the tile screenshot to read as a picture rather than a texture swatch. */
    static final int ART_HEIGHT = 44;

    private BuilderTypeControls() {}

    /**
     * The mode, as the picture it was on the Train Builder picker.
     *
     * <p>Carrying the image through is what makes the picker and these screens read as the same
     * choice rather than as a list of words that happens to match a wall of images.</p>
     *
     * @param preview the selected template's own photo, asked for each frame; null falls back to the
     *                mode's tile art
     */
    static BuilderModeArtButton mode(int x, int y, int width, BuilderMode initial,
                                     Supplier<BuilderPhotoTextures.Photo> preview,
                                     Consumer<BuilderMode> onChange) {
        return new BuilderModeArtButton(x, y, width, ART_HEIGHT, initial, preview, onChange);
    }

    /**
     * What you're authoring within that mode. Absent for the track modes, which have no sub types.
     *
     * <p>The values are the mode's own — {@code BuilderNewOptions.subTypesFor} — not every sub type
     * there is, because Whole Carriage means nothing from inside a carriage. The initial value is
     * clamped for the same reason: the screens carry one selection across a mode change, and
     * {@code CycleButton} throws on an initial value that isn't in its list.</p>
     */
    static CycleButton<BuilderNewOptions.SubType> subType(int x, int y, int width, int height,
                                                          BuilderMode mode,
                                                          BuilderNewOptions.SubType initial,
                                                          Consumer<BuilderNewOptions.SubType> onChange) {
        return CycleButton.builder(BuilderTypeControls::subTypeLabel)
                .withValues(BuilderNewOptions.subTypesFor(mode))
                .withInitialValue(BuilderNewOptions.clampSubType(mode, initial))
                .displayOnlyValue()
                .create(x, y, width, height,
                        Component.translatable("gui.dungeontrain.builder.new.subtype"),
                        (button, value) -> onChange.accept(value));
    }

    /** Which part is being authored. Only shown when the sub type is Parts. */
    static CycleButton<String> partKind(int x, int y, int width, int height, String initial,
                                        Consumer<String> onChange) {
        return CycleButton.builder(BuilderTypeControls::plainLabel)
                .withValues(BuilderNewOptions.PART_KINDS)
                .withInitialValue(initial)
                .displayOnlyValue()
                .create(x, y, width, height,
                        Component.translatable("gui.dungeontrain.builder.new.part_kind"),
                        (button, value) -> onChange.accept(value));
    }

    /**
     * Lay {@code controls} out two per row, and answer the Y the next row would start at.
     *
     * <p>A control with no partner takes the whole row rather than leaving a hole beside it, which
     * is the common case — only Parts has a second control to pair with.</p>
     */
    static int layoutRows(List<? extends AbstractWidget> controls, int x, int width, int startY,
                          int rowHeight, int rowGap) {
        int y = startY;
        for (int i = 0; i < controls.size(); i += 2) {
            AbstractWidget left = controls.get(i);
            AbstractWidget right = i + 1 < controls.size() ? controls.get(i + 1) : null;
            left.setX(x);
            left.setY(y);
            if (right == null) {
                left.setWidth(width);
            } else {
                left.setWidth(BuilderPauseMenuLayout.leftHalfWidth(width));
                right.setX(BuilderPauseMenuLayout.rightHalfX(x, width));
                right.setY(y);
                right.setWidth(BuilderPauseMenuLayout.rightHalfWidth(width));
            }
            y += rowHeight + rowGap;
        }
        return y;
    }

    static Component subTypeLabel(BuilderNewOptions.SubType value) {
        return Component.translatable(value.labelKey());
    }

    /**
     * Template, stage and part-kind ids are authored strings rather than translation keys, so they
     * get read rather than looked up — but read as words, not as filenames.
     */
    static Component plainLabel(String value) {
        return Component.literal(BuilderLabels.pretty(value));
    }
}
