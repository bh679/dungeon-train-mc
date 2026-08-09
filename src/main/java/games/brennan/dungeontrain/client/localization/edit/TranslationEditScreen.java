package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Edits one translation unit: the English above, the translation below, and nothing else.
 *
 * <p>Saving applies immediately — {@link TranslationOverrides#setLang} reinstalls the language
 * overlay before this screen closes, so a player editing a title-screen string watches it change
 * behind them. Book fields save the same way but only take effect on an integrated server, which
 * the screen says outright rather than leaving the player to wonder why the prose is unchanged.
 * </p>
 */
public final class TranslationEditScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int ROW_H = 20;
    private static final int GAP = 4;
    private static final int TOP = 30;
    private static final int LABEL_COLOUR = 0xFFA0A0A0;
    private static final int SOURCE_COLOUR = 0xFFFFFFFF;
    private static final int NOTE_COLOUR = 0xFFD8A657;
    private static final int AI_COLOUR = 0xFF5B9BD5;
    /** Lines of English shown before it is truncated; the longest shipped string is ~314 chars. */
    private static final int MAX_SOURCE_LINES = 6;

    private final TranslationScreen parent;
    private final String locale;
    private final TranslationUnit unit;

    private MultiLineEditBox editor;
    private List<FormattedCharSequence> sourceLines = List.of();

    public TranslationEditScreen(TranslationScreen parent, String locale, TranslationUnit unit) {
        super(Component.literal(unit.label()));
        this.parent = parent;
        this.locale = locale;
        this.unit = unit;
    }

    @Override
    protected void init() {
        int contentWidth = width - MARGIN * 2;
        sourceLines = font.split(FormattedText.of(unit.source().isEmpty()
            ? Component.translatable("gui.dungeontrain.translate.no_source").getString()
            : unit.source()), contentWidth);
        if (sourceLines.size() > MAX_SOURCE_LINES) {
            sourceLines = sourceLines.subList(0, MAX_SOURCE_LINES);
        }

        int editorTop = TOP + font.lineHeight * (sourceLines.size() + 2) + GAP * 3;
        int bottomRow = height - MARGIN - ROW_H;
        int noteHeight = unit.type() == TranslationUnit.Type.BOOK ? font.lineHeight + GAP : 0;
        int editorHeight = Math.max(ROW_H * 2, bottomRow - GAP - noteHeight - editorTop);

        editor = new MultiLineEditBox(font, MARGIN, editorTop, contentWidth, editorHeight,
            Component.translatable("gui.dungeontrain.translate.edit.hint"),
            Component.translatable("gui.dungeontrain.translate.edit.label"));
        editor.setCharacterLimit(TranslationEdits.MAX_VALUE_CHARS);
        editor.setValue(currentValue());
        addRenderableWidget(editor);
        setInitialFocus(editor);

        int buttons = 3;
        int buttonWidth = (contentWidth - GAP * (buttons - 1)) / buttons;
        int x = MARGIN;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.edit.save"), b -> save())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.edit.revert"), b -> revert())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
        x += buttonWidth + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(x, bottomRow, buttonWidth, ROW_H).build());
    }

    /** What the box opens with: this player's override if any, else the shipped translation. */
    private String currentValue() {
        String override = TranslationScreen.overrideOf(unit, TranslationOverrides.merged());
        return override != null ? override : unit.shipped();
    }

    private void save() {
        String value = editor.getValue();
        // Saving text identical to the shipped translation is a revert, not an override: storing
        // it would put the key in the submission and in the "edited" filter for no reason.
        String toStore = value.equals(unit.shipped()) ? "" : value;
        if (unit.type() == TranslationUnit.Type.BOOK) {
            TranslationOverrides.setBook(unit.id(), toStore);
        } else {
            TranslationOverrides.setLang(unit.id(), toStore);
        }
        close();
    }

    private void revert() {
        if (unit.type() == TranslationUnit.Type.BOOK) {
            TranslationOverrides.setBook(unit.id(), "");
        } else {
            TranslationOverrides.setLang(unit.id(), "");
        }
        close();
    }

    private void close() {
        parent.onEditsChanged();
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);

        int y = TOP;
        Component heading = unit.aiUnreviewed()
            ? Component.translatable("gui.dungeontrain.translate.edit.source_ai")
            : Component.translatable("gui.dungeontrain.translate.edit.source");
        g.drawString(font, heading, MARGIN, y, unit.aiUnreviewed() ? AI_COLOUR : LABEL_COLOUR, false);
        y += font.lineHeight + GAP;
        for (FormattedCharSequence line : sourceLines) {
            g.drawString(font, line, MARGIN, y, SOURCE_COLOUR, false);
            y += font.lineHeight;
        }

        if (unit.type() == TranslationUnit.Type.BOOK) {
            g.drawString(font, Component.translatable("gui.dungeontrain.translate.edit.book_note"),
                MARGIN, height - MARGIN - ROW_H - GAP - font.lineHeight, NOTE_COLOUR, false);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
