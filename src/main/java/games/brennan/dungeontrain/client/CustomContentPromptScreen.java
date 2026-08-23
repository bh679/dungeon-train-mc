package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.CustomContentPreference;
import games.brennan.dungeontrain.net.CustomContentChoicePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * "Custom train content" — shown once per world, at join, when Train Editor
 * content (the player's own edits or an imported dtpack) is active.
 *
 * <p>Continue plays the world with that content, which keeps the run in Free
 * Play. Disable Custom Changes turns the content off for this world from here
 * on, so the bundled game runs and stats count. A "Remember decision" checkbox
 * persists the answer to {@link ClientDisplayConfig} and is changeable later in
 * Options → Dungeon Train.</p>
 *
 * <p><b>Full-screen</b>, unlike the narrow tooltip-framed
 * {@link FreePlayConfirmScreen}: that one interrupts an action the player just
 * took and should stay out of the way, while this one greets them as the world
 * opens and is the only thing they should be looking at. The world is covered
 * by an opaque backdrop rather than the usual dim, so nothing competes with the
 * choice.</p>
 */
public final class CustomContentPromptScreen extends Screen {

    /** Opaque backdrop — this is the whole screen, not a panel floating over the world. */
    private static final int BACKDROP = 0xF00A0A12;
    /** Hairline rules above and below the title, the one piece of chrome the screen keeps. */
    private static final int COLOUR_RULE = 0x40FFFFFF;

    private static final int TITLE_TEAL = 0xFF5BC8C2;
    private static final int COLOUR_BODY = 0xFFE0E0E0;
    private static final int COLOUR_CONSEQ = 0xFFB8B8B8;
    private static final int COLOUR_PACKAGES = 0xFF7E7E8C;

    /** Text column width. Capped so lines stay readable on a wide monitor. */
    private static final int MAX_TEXT_W = 340;
    private static final int BUTTON_W = 220;
    private static final int BUTTON_H = 20;
    private static final int LINE_GAP = 2;
    private static final int SECTION_GAP = 12;
    private static final int TITLE_GAP = 14;
    private static final int BUTTON_GAP = 6;

    private final String packages;
    private Checkbox rememberBox;
    private boolean responded = false;

    /** One block of pre-wrapped lines with its colour, laid out top-down in {@link #render}. */
    private record Block(List<FormattedCharSequence> lines, int colour) {}

    private final List<Block> blocks = new ArrayList<>();
    private int titleY;
    private int bodyTopY;

    public CustomContentPromptScreen(String packages) {
        super(Component.translatable("gui.dungeontrain.custom_content.title"));
        this.packages = packages;
    }

    @Override
    protected void init() {
        blocks.clear();
        int textW = Math.min(MAX_TEXT_W, this.width - 40);
        int cx = this.width / 2;
        int lh = this.font.lineHeight;

        addBlock("gui.dungeontrain.custom_content.body", textW, COLOUR_BODY);
        addBlock("gui.dungeontrain.custom_content.keep", textW, COLOUR_CONSEQ);
        addBlock("gui.dungeontrain.custom_content.disable", textW, COLOUR_CONSEQ);
        if (!packages.isBlank()) {
            blocks.add(new Block(this.font.split(
                Component.translatable("gui.dungeontrain.custom_content.packages", packages), textW),
                COLOUR_PACKAGES));
        }

        // Measure the whole stack, then centre it vertically — the screen is full-bleed, so the
        // content should sit in the middle of it rather than hang off a fixed top margin.
        int textH = 0;
        for (Block b : blocks) textH += b.lines().size() * (lh + LINE_GAP) + SECTION_GAP;
        int controlsH = BUTTON_H /* checkbox */ + SECTION_GAP + 2 * BUTTON_H + BUTTON_GAP;
        int totalH = lh + TITLE_GAP + textH + SECTION_GAP + controlsH;

        titleY = Math.max(20, (this.height - totalH) / 2);
        bodyTopY = titleY + lh + TITLE_GAP;

        int y = bodyTopY + textH + SECTION_GAP;

        rememberBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.custom_content.remember"), this.font)
            .pos(cx - BUTTON_W / 2, y)
            .selected(!ClientDisplayConfig.getCustomContentPreference().asks())
            .build();
        addRenderableWidget(rememberBox);
        y += BUTTON_H + SECTION_GAP;

        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.custom_content.continue"), b -> respond(true))
            .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
        y += BUTTON_H + BUTTON_GAP;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.custom_content.disable_button"), b -> respond(false))
            .bounds(cx - BUTTON_W / 2, y, BUTTON_W, BUTTON_H).build());
    }

    private void addBlock(String translationKey, int textW, int colour) {
        blocks.add(new Block(this.font.split(Component.translatable(translationKey), textW), colour));
    }

    private void respond(boolean keepContent) {
        if (responded) return;
        responded = true;
        if (rememberBox != null && rememberBox.selected()) {
            ClientDisplayConfig.setCustomContentPreference(
                keepContent ? CustomContentPreference.CONTINUE : CustomContentPreference.DISABLE);
        }
        DungeonTrainNet.sendToServer(new CustomContentChoicePacket(keepContent));
        onClose();
    }

    /**
     * ESC answers "continue" rather than cancelling. There is nothing to back out of here — the
     * world is already running with the content — so dismissing the prompt must mean "leave things
     * as they are", not silently disable someone's builds.
     */
    @Override
    public void onClose() {
        if (!responded) {
            responded = true;
            DungeonTrainNet.sendToServer(new CustomContentChoicePacket(true));
        }
        super.onClose();
    }

    /** Full-bleed: paint our own opaque backdrop instead of the usual world dim. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BACKDROP);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int lh = this.font.lineHeight;
        int textW = Math.min(MAX_TEXT_W, this.width - 40);

        g.drawCenteredString(this.font, this.title, cx, titleY, TITLE_TEAL);
        int ruleY = titleY + lh + 5;
        g.fill(cx - textW / 2, ruleY, cx + textW / 2, ruleY + 1, COLOUR_RULE);

        int y = bodyTopY;
        for (Block block : blocks) {
            for (FormattedCharSequence line : block.lines()) {
                g.drawCenteredString(this.font, line, cx, y, block.colour());
                y += lh + LINE_GAP;
            }
            y += SECTION_GAP;
        }
    }
}
