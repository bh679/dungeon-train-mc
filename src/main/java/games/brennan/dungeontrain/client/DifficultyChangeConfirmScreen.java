package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.Difficulty;

import java.util.ArrayList;
import java.util.List;

/**
 * "Change difficulty to X?" — shown before a difficulty change commits (see
 * {@code DifficultyChangeConfirmEvents}).
 *
 * <p>In a singleplayer run it offers <b>Save for next run</b> and <b>Abandon run &amp; restart now</b>
 * ({@link DifficultyChangeConfirmClient}); elsewhere (a remote server, the Train Editor) it offers
 * <b>Change difficulty</b> in place, plus Save for next run where a next world can be created here.
 * With {@code difficultyIsolatedStash} on, the loss lines say what switching profiles costs.</p>
 *
 * <p>Drawn with the same vanilla tooltip frame as {@link FreePlayConfirmScreen} so the two
 * confirmations read as one family of prompt. Cancel (or ESC) returns to {@link #parent()} — for the
 * Options screen that re-runs its {@code init()}, which rebuilds the Difficulty button from the
 * level's real difficulty, so a cancelled change visually snaps back on its own.</p>
 */
public final class DifficultyChangeConfirmScreen extends Screen {

    // Vanilla tooltip palette (matches FreePlayConfirmScreen's frame).
    private static final int FRAME_BG = 0xF0100010;
    private static final int FRAME_BORDER_TOP = 0x505000FF;
    private static final int FRAME_BORDER_BOTTOM = 0x5028007F;

    private static final int TITLE_WARN = 0xFFFF7B5B;
    private static final int COLOUR_BODY = 0xFFE0E0E0;
    private static final int COLOUR_LOSS = 0xFFFF7B5B;
    private static final int COLOUR_NOTE = 0xFFB8B8B8;
    private static final int COLOUR_SEPARATOR = 0x40FFFFFF;

    private static final int MAX_PANEL_W = 220;
    private static final int PADDING = 12;
    private static final int LINE_GAP = 1;
    private static final int SECTION_GAP = 7;
    private static final int TITLE_SEP_GAP = 6;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;

    private final Screen parent;
    private final Difficulty from;
    private final Difficulty to;
    private final boolean targetEmpty;
    private final boolean isolated;
    /** A singleplayer run: "restart now" is on offer and "change in place" is not. */
    private final boolean runWorld;
    /** An integrated server: this client creates the next world, so "save for next run" means something. */
    private final boolean singleplayer;
    private boolean responded = false;

    // Layout, computed in init() and reused by render().
    private int panelX, panelY, panelW, panelH;
    private int titleRelY, bodyRelY, lossRelY, noteRelY;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> lossLines = List.of();
    private List<FormattedCharSequence> noteLines = List.of();

    public DifficultyChangeConfirmScreen(Screen parent, Difficulty from, Difficulty to, boolean targetEmpty,
                                         boolean isolated, boolean runWorld, boolean singleplayer) {
        super(Component.translatable("gui.dungeontrain.difficulty_confirm.title", to.getDisplayName()));
        this.parent = parent;
        this.from = from;
        this.to = to;
        this.targetEmpty = targetEmpty;
        this.isolated = isolated;
        this.runWorld = runWorld;
        this.singleplayer = singleplayer;
    }

    /** One answer: its label and what it does. */
    private record Choice(String key, Runnable action) {}

    /** The answers on offer here, in button order (Cancel is always added last). */
    private List<Choice> choices() {
        List<Choice> out = new ArrayList<>();
        if (singleplayer) {
            out.add(new Choice("gui.dungeontrain.difficulty_confirm.next_run",
                    () -> DifficultyChangeConfirmClient.saveForNextRun(to)));
        }
        if (runWorld) {
            out.add(new Choice("gui.dungeontrain.difficulty_confirm.restart_now",
                    () -> DifficultyChangeConfirmClient.restartNow(to)));
        } else {
            out.add(new Choice("gui.dungeontrain.difficulty_confirm.change",
                    () -> DifficultyChangeConfirmClient.applyNow(to)));
        }
        return out;
    }

    /** The screen the prompt was opened over — restored when it closes. */
    public Screen parent() {
        return parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(MAX_PANEL_W, this.width - 40);
        int innerW = panelW - 2 * PADDING;
        int lh = this.font.lineHeight;

        bodyLines = this.font.split(Component.translatable(runWorld
                ? "gui.dungeontrain.difficulty_confirm.body_run"
                : "gui.dungeontrain.difficulty_confirm.body"), innerW);

        List<FormattedCharSequence> loss = new ArrayList<>();
        if (runWorld) {
            // Restarting ends this run for real — say so before anything about gear.
            loss.addAll(this.font.split(Component.translatable(
                "gui.dungeontrain.difficulty_confirm.restart_dies", from.getDisplayName()), innerW));
        }
        if (isolated) {
            loss.addAll(this.font.split(Component.translatable(runWorld
                ? "gui.dungeontrain.difficulty_confirm.loss_run"
                : "gui.dungeontrain.difficulty_confirm.loss"), innerW));
            if (targetEmpty) {
                loss.addAll(this.font.split(
                    Component.translatable("gui.dungeontrain.difficulty_confirm.empty", to.getDisplayName()), innerW));
            }
        }
        lossLines = List.copyOf(loss);

        noteLines = isolated
            ? this.font.split(
                Component.translatable("gui.dungeontrain.difficulty_confirm.kept", from.getDisplayName()), innerW)
            : this.font.split(Component.translatable("gui.dungeontrain.difficulty_confirm.stats"), innerW);
        List<Choice> choices = choices();

        int y = PADDING;
        titleRelY = y;   y += lh + TITLE_SEP_GAP;
        bodyRelY = y;    y += bodyLines.size() * (lh + LINE_GAP) + SECTION_GAP;
        lossRelY = y;    y += lossLines.size() * (lh + LINE_GAP) + SECTION_GAP;
        noteRelY = y;    y += noteLines.size() * (lh + LINE_GAP) + SECTION_GAP;
        int buttonsRelY = y;
        y += (choices.size() + 1) * (BUTTON_H + BUTTON_GAP) - BUTTON_GAP + PADDING;
        panelH = y;

        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int by = panelY + buttonsRelY;
        for (Choice choice : choices) {
            addRenderableWidget(Button.builder(Component.translatable(choice.key()), b -> respond(choice.action()))
                .bounds(panelX + PADDING, by, innerW, BUTTON_H).build());
            by += BUTTON_H + BUTTON_GAP;
        }
        addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.difficulty_confirm.cancel"), b -> onClose())
            .bounds(panelX + PADDING, by, innerW, BUTTON_H).build());
    }

    /**
     * Act, then go back to the screen the prompt opened over — acting first so that screen re-inits
     * showing the remembered difficulty. "Restart now" closes every screen itself, and that is left be.
     */
    private void respond(Runnable action) {
        if (responded) return;
        responded = true;
        action.run();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this) {
            mc.setScreen(parent);
        }
    }

    @Override
    public void onClose() {
        if (!responded) {
            responded = true;
            DifficultyChangeConfirmClient.cancel();
        }
        // Back to where the player was — re-initialising the Options screen resyncs its Difficulty
        // button with the difficulty that is actually in force.
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        drawFrame(g, panelX, panelY, panelX + panelW, panelY + panelH);

        int cx = panelX + panelW / 2;
        int lh = this.font.lineHeight;

        g.drawCenteredString(this.font, this.title, cx, panelY + titleRelY, TITLE_WARN);
        int sepY = panelY + titleRelY + lh + 2;
        g.fill(panelX + 10, sepY, panelX + panelW - 10, sepY + 1, COLOUR_SEPARATOR);

        int y = panelY + bodyRelY;
        for (FormattedCharSequence line : bodyLines) { g.drawCenteredString(this.font, line, cx, y, COLOUR_BODY); y += lh + LINE_GAP; }
        y = panelY + lossRelY;
        for (FormattedCharSequence line : lossLines) { g.drawCenteredString(this.font, line, cx, y, COLOUR_LOSS); y += lh + LINE_GAP; }
        y = panelY + noteRelY;
        for (FormattedCharSequence line : noteLines) { g.drawCenteredString(this.font, line, cx, y, COLOUR_NOTE); y += lh + LINE_GAP; }
    }

    /** Vanilla tooltip-style frame: dark fill + purple gradient border. */
    private static void drawFrame(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, FRAME_BG);
        g.fill(x0, y0, x1, y0 + 1, FRAME_BORDER_TOP);
        g.fill(x0, y1 - 1, x1, y1, FRAME_BORDER_BOTTOM);
        g.fillGradient(x0, y0 + 1, x0 + 1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);
        g.fillGradient(x1 - 1, y0 + 1, x1, y1 - 1, FRAME_BORDER_TOP, FRAME_BORDER_BOTTOM);
    }
}
