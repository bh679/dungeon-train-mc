package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import net.minecraft.ChatFormatting;
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
 * "Delete this build from the relay?" — the one question My Builds' trash button asks.
 *
 * <p>Asked because the answer cannot be taken back: the relay drops the row and everything hanging
 * off it, and a build another world has already loaded is the only copy that survives. The hint
 * says the two things a person deciding needs to know — that it is for good, and that their local
 * template is not what is being deleted.</p>
 *
 * <p>Laid out as {@link BuilderProfileChoiceScreen} lays out its questions — wrapped title, wrapped
 * note, a column of answers — but not one of them: that family answers a download with a
 * resolution, and this answers with a yes.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileDeleteScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;
    private static final int LINE_HEIGHT = 11;
    private static final int TEXT_GAP = 6;
    private static final int NOTE_COLOUR = 0xA0A0A0;

    private final Screen lastScreen;
    private final Runnable onConfirm;

    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> hintLines = List.of();
    private int buttonsTop;

    /**
     * @param lastScreen the profile screen to return to, whichever way this is answered
     * @param buildName  the build's stored name, prettied here for the title
     * @param onConfirm  run after the screen has gone back to {@code lastScreen}, on a yes only
     */
    public BuilderProfileDeleteScreen(Screen lastScreen, String buildName, Runnable onConfirm) {
        super(Component.translatable("gui.dungeontrain.builder.profile.delete.title",
                Component.literal(BuilderLabels.pretty(buildName))));
        this.lastScreen = lastScreen;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int wrapWidth = Math.min(this.width - 40, 320);
        this.titleLines = this.font.split(getTitle(), wrapWidth);
        this.hintLines = this.font.split(
                Component.translatable("gui.dungeontrain.builder.profile.delete.hint"), wrapWidth);
        this.buttonsTop = this.height / 2 - 30;
        int left = this.width / 2 - BUTTON_WIDTH / 2;
        // The destructive answer in red, and first: this screen exists to make the player look at
        // it, not to hide it behind a safe-looking default.
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.builder.profile.delete.confirm")
                                .withStyle(ChatFormatting.RED),
                        b -> {
                            this.minecraft.setScreen(lastScreen);
                            onConfirm.run();
                        })
                .bounds(left, buttonsTop, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left, buttonsTop + BUTTON_HEIGHT + BUTTON_GAP * 3, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // Laid out upwards from the buttons, so a hint that wraps to three lines pushes itself clear
        // of them instead of into them — the same reasoning as the choice screens.
        int y = buttonsTop - TEXT_GAP - hintLines.size() * LINE_HEIGHT;
        for (FormattedCharSequence line : hintLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, NOTE_COLOUR);
            y += LINE_HEIGHT;
        }
        y = buttonsTop - TEXT_GAP * 2 - hintLines.size() * LINE_HEIGHT - titleLines.size() * LINE_HEIGHT;
        for (FormattedCharSequence line : titleLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
