package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.Consumer;

/**
 * Who a string's character is — pronouns, gender, rough age — opened from the editor's heading row.
 *
 * <p>Reference for the translator, not something they translate, so the card is written in English
 * like the source pane above the edit box. A field the author never settled says so instead of
 * being left out: "not stated" is itself an answer a translator can work with — it says the
 * language is free to choose.</p>
 */
public final class TranslationCharacterScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int GAP = 8;
    private static final int TEXT_TOP = 32;
    private static final int LABEL_COLOUR = 0xFFA0A0A0;

    /** What the translator chose. */
    public enum Choice { BACK, SHOW_LINES }

    private final TranslationCharacters.Character character;
    private final Consumer<Choice> onChoice;

    public TranslationCharacterScreen(TranslationCharacters.Character character,
                                      Consumer<Choice> onChoice) {
        super(Component.literal(character.name()));
        this.character = character;
        this.onChoice = onChoice;
    }

    @Override
    protected void init() {
        int textWidth = Math.min(width - 40, 360);
        MutableComponent body = Component.empty();
        line(body, "pronouns", character.pronouns());
        line(body, "gender", character.gender());
        line(body, "age", character.age());
        line(body, "about", character.about());
        if (!character.notes().isEmpty()) {
            line(body, "notes", character.notes());
        }
        addRenderableWidget(new MultiLineTextWidget(width / 2 - textWidth / 2, TEXT_TOP, body, font)
            .setMaxWidth(textWidth));

        int buttonWidth = Math.min(width - 40, 220);
        int x = width / 2 - buttonWidth / 2;
        int y = height - ROW_H * 2 - GAP * 3;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.character.show_lines"),
            b -> onChoice.accept(Choice.SHOW_LINES)).bounds(x, y, buttonWidth, ROW_H).build());
        y += ROW_H + GAP / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK,
            b -> onChoice.accept(Choice.BACK)).bounds(x, y, buttonWidth, ROW_H).build());
    }

    /** "Pronouns: she/her", the label grey and the value white; a blank value says so. */
    private static void line(MutableComponent body, String field, String value) {
        if (!body.getSiblings().isEmpty()) {
            body.append("\n\n");
        }
        body.append(Component.translatable("gui.dungeontrain.translate.character." + field)
            .withColor(LABEL_COLOUR));
        body.append(" ");
        body.append(value.isEmpty()
            ? Component.translatable("gui.dungeontrain.translate.character.not_stated")
                .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY)
            : Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    /** Escape goes back to the edit box, text untouched. */
    @Override
    public void onClose() {
        onChoice.accept(Choice.BACK);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
    }
}
