package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.net.BuilderSuggestionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * The developer edited one of the player's builds and sent the edit back: what they said, and the
 * player's choice — take the edit into their build, or keep theirs. Either answer goes to the server
 * as a {@link BuilderSuggestionPacket}; Cancel decides nothing and the edit keeps waiting.
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSuggestionScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int LINE_HEIGHT = 11;
    private static final int TEXT_GAP = 6;
    private static final int MAX_COMMENT_LINES = 8;
    private static final int NOTE_COLOUR = 0xA0A0A0;

    private final Screen lastScreen;
    private final int relayId;
    private final String buildName;
    private final String comment;
    private List<FormattedCharSequence> commentLines = List.of();
    private int buttonsTop;

    public BuilderSuggestionScreen(Screen lastScreen, int relayId, String buildName, String comment) {
        super(Component.translatable("gui.dungeontrain.builder.profile.suggestion.title",
                BuilderLabels.pretty(buildName == null ? "" : buildName)));
        this.lastScreen = lastScreen;
        this.relayId = relayId;
        this.buildName = buildName == null ? "" : buildName;
        this.comment = comment == null ? "" : comment;
    }

    @Override
    protected void init() {
        int wrap = Math.min(this.width - 40, 320);
        List<FormattedCharSequence> lines = this.font.split(Component.literal(comment), wrap);
        this.commentLines = lines.size() > MAX_COMMENT_LINES ? lines.subList(0, MAX_COMMENT_LINES) : lines;
        int block = LINE_HEIGHT * (2 + commentLines.size()) + TEXT_GAP * 2 + (BUTTON_HEIGHT + BUTTON_GAP) * 3;
        int top = Math.max(8, (this.height - block) / 2);
        this.buttonsTop = top + LINE_HEIGHT * (2 + commentLines.size()) + TEXT_GAP * 2;
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.builder.profile.suggestion.accept"),
                b -> answer(true)).bounds(x, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.builder.profile.suggestion.keep"),
                b -> answer(false)).bounds(x, buttonsTop + BUTTON_HEIGHT + BUTTON_GAP, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, buttonsTop + (BUTTON_HEIGHT + BUTTON_GAP) * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void answer(boolean accept) {
        this.minecraft.setScreen(lastScreen);
        // The profile re-reads once the relay answers; the chat line says how it went.
        DungeonTrainNet.sendToServer(new BuilderSuggestionPacket(relayId, accept));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y = buttonsTop - TEXT_GAP * 2 - LINE_HEIGHT * (2 + commentLines.size());
        g.drawCenteredString(this.font, this.title, this.width / 2, y, 0xFFFFFF);
        y += LINE_HEIGHT + TEXT_GAP;
        g.drawCenteredString(this.font, Component.translatable("gui.dungeontrain.builder.profile.suggestion.hint"),
                this.width / 2, y, NOTE_COLOUR);
        y += LINE_HEIGHT + TEXT_GAP;
        for (FormattedCharSequence line : commentLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
