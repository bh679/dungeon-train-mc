package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.cheat.ConfigReset;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;

/**
 * Told at launch, once, that this install's Dungeon Train config no longer matches the one the
 * game is balanced against — with the exact settings that changed, what it costs, and a button
 * that puts them back.
 *
 * <p>Shown on the TITLE screen rather than on world join because that is the only moment the fix
 * is cheap: the configs are read once at launch, so a reset here needs one restart, while the same
 * discovery mid-run means the session is already Free Play whatever the player does.</p>
 *
 * <p>Deliberately not framed as an accusation. Changing a config is a legitimate thing to do; the
 * screen states the trade (global stats and cross-world advancements stop persisting) and lets the
 * player keep their changes with equal weight. "Keep my changes" is remembered against the exact
 * deviation, so this asks again only if something ELSE changes — see
 * {@link ConfigDeviationPromptHandler}.</p>
 *
 * <p>Same flat card as {@link PoliticalFilterPromptScreen}, so every one-time question a player
 * meets on the title screen looks like it came from the same place. Client-only — never
 * class-loaded on a dedicated server.</p>
 */
public final class ConfigDeviationScreen extends Screen {

    private static final String KEY_TITLE = "gui.dungeontrain.config_deviation.title";
    private static final String KEY_BODY = "gui.dungeontrain.config_deviation.body";
    private static final String KEY_MORE = "gui.dungeontrain.config_deviation.more";
    private static final String KEY_RESET = "gui.dungeontrain.config_deviation.reset";
    private static final String KEY_KEEP = "gui.dungeontrain.config_deviation.keep";
    private static final String KEY_DONE_TITLE = "gui.dungeontrain.config_deviation.done.title";
    private static final String KEY_DONE_BODY = "gui.dungeontrain.config_deviation.done.body";
    private static final String KEY_DONE_FAIL = "gui.dungeontrain.config_deviation.done.fail";
    private static final String KEY_DONE_BACKUP = "gui.dungeontrain.config_deviation.done.backup";
    private static final String KEY_CLOSE = "gui.dungeontrain.config_deviation.close";

    /** Deviations listed in full; beyond this the rest are summarised as "+N more". */
    private static final int MAX_LISTED = 8;

    // Flat card geometry — mirrors PoliticalFilterPromptScreen / DP's NetworkConsentScreen.
    private static final int CARD_W = 300;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;

    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 10;
    private static final int GAP_LIST = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_LIST = 0xFF9A9AA2;

    private final Screen previousScreen;
    private final List<String> deviations;
    private final String signature;

    /** Null until the player resets; then the screen re-lays itself out as the "done" state. */
    private ConfigReset.Result result = null;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> listLines = List.of();
    private int titleY;
    private int bodyY;
    private int listY;

    public ConfigDeviationScreen(Screen previousScreen, List<String> deviations, String signature) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
        this.deviations = List.copyOf(deviations);
        this.signature = signature;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        bodyLines = font.split(bodyText(), innerWidth);
        listLines = new ArrayList<>();
        for (Component line : listText()) {
            listLines.addAll(font.split(line, innerWidth));
        }
        listLines = List.copyOf(listLines);

        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + listLines.size() * LINE_STEP + GAP_LIST
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
        listY = cursor;
        cursor += listLines.size() * LINE_STEP + GAP_LIST;

        int innerLeft = panelX + PAD;
        if (result != null) {
            addRenderableWidget(Button.builder(Component.translatable(KEY_CLOSE), b -> close())
                    .bounds(innerLeft, cursor, innerWidth, BUTTON_H)
                    .build());
            return;
        }
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY_RESET), b -> reset())
                .bounds(innerLeft, cursor, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_KEEP), b -> keep())
                .bounds(innerLeft + buttonW + BUTTON_GAP, cursor, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    private Component bodyText() {
        if (result == null) return Component.translatable(KEY_BODY);
        return Component.translatable(result.success() ? KEY_DONE_BODY : KEY_DONE_FAIL);
    }

    /** The middle block: what changed, or — once reset — where the old files went. */
    private List<Component> listText() {
        List<Component> lines = new ArrayList<>();
        if (result != null) {
            for (ConfigReset.Moved moved : result.moved()) {
                lines.add(Component.translatable(KEY_DONE_BACKUP, moved.file(), moved.backup()));
            }
            return lines;
        }
        int listed = Math.min(deviations.size(), MAX_LISTED);
        for (int i = 0; i < listed; i++) {
            lines.add(Component.literal(deviations.get(i)));
        }
        if (deviations.size() > listed) {
            lines.add(Component.translatable(KEY_MORE, deviations.size() - listed));
        }
        return lines;
    }

    /**
     * Move every governed config aside and switch to the "done" state. The acknowledgement is
     * cleared too: the config is about to be regenerated at defaults, so any remembered "keep this"
     * no longer describes anything, and a future change should ask again.
     */
    private void reset() {
        result = ConfigReset.run(FMLPaths.CONFIGDIR.get());
        ConfigDeviationPromptHandler.onResetPerformed();
        rebuildWidgets();
    }

    /** Remember this exact deviation as accepted, and go back. */
    private void keep() {
        ConfigDeviationPromptHandler.onKeptChanges(signature);
        close();
    }

    private void close() {
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

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, titleText(), centerX, titleY, COLOR_TITLE);

        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }

        // Left-aligned: these are key=value settings the player may want to find in the file.
        int ly = listY;
        for (FormattedCharSequence line : listLines) {
            graphics.drawString(font, line, panelX + PAD, ly, COLOR_LIST, false);
            ly += LINE_STEP;
        }
    }

    private Component titleText() {
        return Component.translatable(result == null ? KEY_TITLE : KEY_DONE_TITLE);
    }

    /**
     * Esc means "not now" — it does NOT record the deviation as accepted, so the prompt returns on
     * the next launch. Dismissing a question about your own save data shouldn't quietly answer it.
     */
    @Override
    public void onClose() {
        close();
    }
}
