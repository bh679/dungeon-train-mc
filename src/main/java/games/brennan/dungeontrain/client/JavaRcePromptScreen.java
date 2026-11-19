package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.util.JavaRceCheck;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java security warning (see {@link JavaRceNotice} for who sees it). Same flat card as
 * {@link LowMemoryPromptScreen}, in four pages:
 * <ul>
 *   <li>{@link Page#MAIN}: what's wrong in a sentence, then the two fixes as buttons and a
 *       "Continue anyway" text link;</li>
 *   <li>{@link Page#UPDATE}: how to update Java;</li>
 *   <li>{@link Page#ARGS}: the JVM argument, a Copy button and the player's own launcher's path;</li>
 *   <li>{@link Page#CONFIRM}: a second warning before playing unfixed, with "Don't show me again"
 *       (remembered per Java runtime, so a different Java is warned afresh).</li>
 * </ul>
 * Esc steps back a page; on the main page it opens the confirmation, so the warning can't be
 * skipped with one key press.
 *
 * <p>Client-only: never class-loaded on a dedicated server.</p>
 */
public final class JavaRcePromptScreen extends Screen {

    enum Page { MAIN, UPDATE, ARGS, CONFIRM }

    private static final String K = "gui.dungeontrain.java_rce.";

    private static final int CARD_MAX_W = 400;
    private static final int CARD_MIN_W = 280;
    private static final int PAD = 12;
    private static final int LINE_STEP = 11;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int CHECKBOX_H = 20;
    private static final int GAP_TITLE = 8;
    private static final int GAP_SECTION = 7;
    private static final int GAP_BUTTONS = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFF6B5B;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_NOTE = 0xFF909090;
    private static final int COLOR_ARG = 0xFFFFE066;
    private static final int COLOR_ARG_BG = 0xFF0E0E10;

    /** One block of centred text, or the argument box when {@code lines} is null. */
    private record Block(List<FormattedCharSequence> lines, int color, int y) {}

    /** A part of a page: a text block in a colour, or the argument box. */
    private record Part(Component text, int color) {
        static final Part ARG_BOX = new Part(null, COLOR_ARG);
    }

    private final Screen previousScreen;
    private final String whereKey;
    private final String fixedVersion;

    private Page page = Page.MAIN;
    private boolean dontShowAgain;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private int titleY;
    private Component title = Component.empty();
    private final List<Block> blocks = new ArrayList<>();
    private Checkbox dontShowBox;
    private Button copyButton;

    public JavaRcePromptScreen(Screen previousScreen, String whereKey, String fixedVersion) {
        super(Component.translatable(K + "title"));
        this.previousScreen = previousScreen;
        this.whereKey = whereKey;
        this.fixedVersion = fixedVersion;
    }

    private void show(Page next) {
        if (dontShowBox != null) dontShowAgain = dontShowBox.selected();
        page = next;
        rebuildWidgets();
    }

    private List<Part> parts() {
        return switch (page) {
            case MAIN -> List.of(new Part(Component.translatable(K + "intro"), COLOR_BODY));
            case UPDATE -> List.of(
                new Part(Component.translatable(K + "update.body", fixedVersion), COLOR_BODY),
                new Part(Component.translatable(K + "update.note"), COLOR_NOTE));
            case ARGS -> List.of(
                new Part(Component.translatable(K + "args.body"), COLOR_BODY),
                Part.ARG_BOX,
                new Part(Component.translatable(whereKey).append(" ").append(Component.translatable(K + "paste")), COLOR_BODY),
                new Part(Component.translatable(K + "args.note"), COLOR_NOTE));
            case CONFIRM -> List.of(new Part(Component.translatable(K + "confirm.body"), COLOR_BODY));
        };
    }

    private Component pageTitle() {
        return Component.translatable(K + switch (page) {
            case MAIN -> "title";
            case UPDATE -> "update.title";
            case ARGS -> "args.title";
            case CONFIRM -> "confirm.title";
        });
    }

    @Override
    protected void init() {
        blocks.clear();
        dontShowBox = null;
        copyButton = null;
        title = pageTitle();
        int cardW = Math.max(CARD_MIN_W, Math.min(CARD_MAX_W, width - 16));
        int inner = cardW - 2 * PAD;

        // Measure first so the card can be centred, then place.
        List<Part> parts = parts();
        List<List<FormattedCharSequence>> split = new ArrayList<>();
        int h = font.lineHeight + GAP_TITLE;
        for (Part part : parts) {
            List<FormattedCharSequence> lines = part.text() == null ? null : font.split(part.text(), inner);
            split.add(lines);
            h += (lines == null ? argBoxH() : lines.size() * LINE_STEP) + GAP_SECTION;
        }
        h += GAP_BUTTONS - GAP_SECTION + controlsHeight();

        panelW = cardW;
        panelH = PAD + h + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(4, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int y = panelY + PAD;
        titleY = y;
        y += font.lineHeight + GAP_TITLE;
        for (int i = 0; i < parts.size(); i++) {
            List<FormattedCharSequence> lines = split.get(i);
            blocks.add(new Block(lines, parts.get(i).color(), y));
            y += (lines == null ? argBoxH() : lines.size() * LINE_STEP) + GAP_SECTION;
        }
        y += GAP_BUTTONS - GAP_SECTION;
        addControls(panelX + PAD, y, inner);
    }

    private int argBoxH() {
        return font.lineHeight + 8;
    }

    private int controlsHeight() {
        return switch (page) {
            case MAIN -> BUTTON_H + BUTTON_GAP + font.lineHeight;
            case UPDATE, ARGS -> BUTTON_H;
            case CONFIRM -> CHECKBOX_H + BUTTON_GAP + BUTTON_H;
        };
    }

    private void addControls(int left, int y, int inner) {
        int half = (inner - BUTTON_GAP) / 2;
        int rightX = left + half + BUTTON_GAP;
        int rightW = inner - half - BUTTON_GAP;
        switch (page) {
            case MAIN -> {
                addRenderableWidget(Button.builder(Component.translatable(K + "opt_update"), b -> show(Page.UPDATE))
                    .bounds(left, y, half, BUTTON_H).build());
                addRenderableWidget(Button.builder(Component.translatable(K + "opt_args"), b -> show(Page.ARGS))
                    .bounds(rightX, y, rightW, BUTTON_H).build());
                Component link = Component.translatable(K + "continue")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.UNDERLINE);
                int linkW = font.width(link);
                addRenderableWidget(new PlainTextButton(centerX - linkW / 2, y + BUTTON_H + BUTTON_GAP, linkW,
                    font.lineHeight, link, b -> show(Page.CONFIRM), font));
            }
            case UPDATE -> {
                addRenderableWidget(Button.builder(Component.translatable(K + "info"), b -> openInfo())
                    .bounds(left, y, half, BUTTON_H).build());
                addRenderableWidget(Button.builder(Component.translatable(K + "back"), b -> show(Page.MAIN))
                    .bounds(rightX, y, rightW, BUTTON_H).build());
            }
            case ARGS -> {
                copyButton = addRenderableWidget(Button.builder(Component.translatable(K + "copy"), b -> copyArgument())
                    .bounds(left, y, half, BUTTON_H).build());
                addRenderableWidget(Button.builder(Component.translatable(K + "back"), b -> show(Page.MAIN))
                    .bounds(rightX, y, rightW, BUTTON_H).build());
            }
            case CONFIRM -> {
                Component label = Component.translatable(K + "dont_show_again");
                int boxW = Checkbox.getBoxSize(font) + 4 + font.width(label);
                dontShowBox = addRenderableWidget(Checkbox.builder(label, font)
                    .pos(centerX - boxW / 2, y)
                    .selected(dontShowAgain)
                    .build());
                int by = y + CHECKBOX_H + BUTTON_GAP;
                addRenderableWidget(Button.builder(Component.translatable(K + "back"), b -> show(Page.MAIN))
                    .bounds(left, by, half, BUTTON_H).build());
                addRenderableWidget(Button.builder(Component.translatable(K + "continue"), b -> continueAnyway())
                    .bounds(rightX, by, rightW, BUTTON_H).build());
            }
        }
    }

    private void copyArgument() {
        this.minecraft.keyboardHandler.setClipboard(JavaRceCheck.JVM_ARGUMENT);
        if (copyButton != null) copyButton.setMessage(Component.translatable(K + "copied"));
    }

    private void openInfo() {
        String url = JavaRceCheck.ADVISORY_URL;
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            this.minecraft.setScreen(this);
        }, url, true));
    }

    /** Play unfixed. "Don't show me again" remembers this Java, so another Java is warned afresh. */
    private void continueAnyway() {
        if (dontShowBox != null && dontShowBox.selected()) {
            ClientDisplayConfig.setJavaRceWarningDismissedFor(JavaRceNotice.runtimeVersion());
        }
        this.minecraft.setScreen(previousScreen);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, CARD_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, CARD_BORDER);

        int argW = font.width(JavaRceCheck.JVM_ARGUMENT) + 16;
        for (Block block : blocks) {
            if (block.lines() == null) {
                graphics.fill(centerX - argW / 2, block.y(), centerX + argW / 2, block.y() + argBoxH(), COLOR_ARG_BG);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, centerX, titleY, COLOR_TITLE);
        for (Block block : blocks) {
            if (block.lines() == null) {
                graphics.drawCenteredString(font, Component.literal(JavaRceCheck.JVM_ARGUMENT), centerX, block.y() + 4, COLOR_ARG);
                continue;
            }
            int y = block.y();
            for (FormattedCharSequence line : block.lines()) {
                graphics.drawCenteredString(font, line, centerX, y, block.color());
                y += LINE_STEP;
            }
        }
    }

    /** Esc steps back: a fix page or the confirmation returns to the main page; the main page asks first. */
    @Override
    public void onClose() {
        show(page == Page.MAIN ? Page.CONFIRM : Page.MAIN);
    }
}
