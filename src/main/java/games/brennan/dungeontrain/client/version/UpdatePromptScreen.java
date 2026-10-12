package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.version.compare.FullSemver;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.UpdatePage;
import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "A new version is out" — shown once at boot over the title screen when a newer real release is on
 * any launcher ({@link UpdatePromptHandler}). Deliberately minimal and launcher-agnostic: the newest
 * version, then the player's own and how many releases behind it is — nothing else. "See what's new" opens DT's update page, which lists each launcher
 * and what changed ({@link UpdatePage}); the full Versions page stays on the title-screen version line.
 *
 * <p>Same flat card as {@link games.brennan.dungeontrain.client.LowMemoryPromptScreen}, so every
 * one-time title-screen message looks like it came from the same place.</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class UpdatePromptScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.update_prompt.";
    private static final String RELEASES_CLAUSE = "gui.dungeontrain.version.compare.count.releases";

    // Flat card geometry — mirrors LowMemoryPromptScreen so the cards read as one family.
    private static final int CARD_W = 260;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    /** The newest version's name — the good news, in the green the rest of DT's UI uses for "ready". */
    private static final int COLOR_NEWEST = 0x55FF55;
    /** "(N releases behind)" — context, a step dimmer than the body. */
    private static final int COLOR_BEHIND = 0x9A9A9A;

    private final Screen previousScreen;
    private final FullSemver newest;
    private final FullSemver installed;
    private final int releasesBehind;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private int titleY;
    private int bodyY;

    public UpdatePromptScreen(Screen previousScreen, FullSemver newest, FullSemver installed, int releasesBehind) {
        super(Component.translatable(KEY + "title")); // narration title
        this.previousScreen = previousScreen;
        this.newest = newest;
        this.installed = installed;
        this.releasesBehind = releasesBehind;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        // "Dungeon Train vX is out." (the name and version in green) then, on its own line,
        // "You're on vY (N releases behind)" with the parenthetical dimmed.
        Component newestName = Component.translatable(KEY + "newest", newest.toString()).withColor(COLOR_NEWEST);
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(
                Component.translatable(KEY + "body", newestName), innerWidth));
        // "N releases" is the Versions page's already-translated plural clause, so this line needs no family of its own.
        Component releases = PluralRules.clause(ClientLanguage.selected(), RELEASES_CLAUSE, releasesBehind);
        Component behind = Component.translatable(KEY + "behind", releases).withColor(COLOR_BEHIND);
        lines.addAll(font.split(Component.translatable(KEY + "yours", installed.toString(), behind), innerWidth));
        bodyLines = List.copyOf(lines);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + BUTTON_H;
        panelW = CARD_W;
        panelH = PAD + contentH + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP + GAP_BODY;

        // One row: the page on the left, the way out on the right.
        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "see"), b -> openPage())
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "later"), b -> onClose())
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    /** DT's update page, behind vanilla's "open this link?" confirmation; back to this card either way. */
    private void openPage() {
        String url = UpdatePage.url(Optional.of(installed), Platform.current());
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            this.minecraft.setScreen(this);
        }, url, true));
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

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, this.title, centerX, titleY, COLOR_TITLE);
        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }
    }

    /** "Later" and Esc both go back to the title screen. */
    @Override
    public void onClose() {
        this.minecraft.setScreen(previousScreen);
    }
}
