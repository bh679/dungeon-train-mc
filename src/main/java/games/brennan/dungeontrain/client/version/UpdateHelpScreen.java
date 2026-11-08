package games.brennan.dungeontrain.client.version;

import games.brennan.dungeontrain.client.ModpackInstall;
import games.brennan.dungeontrain.client.version.LauncherDetector.Launcher;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.FMLPaths;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * "How to Update" — opened by the {@code ?} on {@link UpdatePromptScreen}. DT can't run a launcher's
 * update from inside the game, so this spells out the steps for the launcher it was started from
 * ({@link LauncherDetector#launcher()}) and for modpack vs bare mod ({@link ModpackInstall}).
 *
 * <p>Launchers that manage the instance (CurseForge, Modrinth, Prism, ATLauncher) are sent to their
 * own Update button. Everything else (PCL, HMCL, the official launcher, MultiMC, unknown) swaps the
 * jar by hand, with Download and Open mods folder buttons. World saves are never mentioned: runs
 * are deleted when they end, and the player's progress lives outside {@code config/}, backed up by
 * Dungeon Backup, so every path keeps it.</p>
 *
 * <p>Client-only — never class-loaded on a dedicated server.</p>
 */
public final class UpdateHelpScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.update_help.";

    /** Which set of steps a player sees. */
    enum Variant {
        /** The modpack, in a launcher that can update the whole pack. */
        PACK_MANAGED,
        /** The bare mod, in a launcher with a per-mod Update button. */
        MOD_MANAGED,
        /** The bare mod, anywhere else: swap the jar. */
        MOD_MANUAL,
        /** The modpack, anywhere else: swap the jar, or import the new pack fresh. */
        PACK_MANUAL;

        boolean manual() {
            return this == MOD_MANUAL || this == PACK_MANUAL;
        }
    }

    // Same flat card as UpdatePromptScreen, a little wider for the steps.
    private static final int CARD_W = 290;
    private static final int PAD = 14;
    private static final int LINE_STEP = 12;
    private static final int PARA_GAP = 4;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 6;
    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 12;

    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_KEPT = 0xFF9A9A9A;

    private final Screen previousScreen;
    private final String downloadUrl;
    private final Launcher launcher;
    private final boolean modpack;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int titleY;
    private int bodyY;
    /** Body lines, with {@code null} marking a paragraph break. */
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> keptLines = List.of();

    public UpdateHelpScreen(Screen previousScreen, String downloadUrl) {
        this(previousScreen, downloadUrl, LauncherDetector.launcher(), ModpackInstall.detected());
    }

    /** Explicit launcher and install kind, so every variant can be shown on a dev client. */
    UpdateHelpScreen(Screen previousScreen, String downloadUrl, Launcher launcher, boolean modpack) {
        super(Component.translatable(KEY + "title"));
        this.previousScreen = previousScreen;
        this.downloadUrl = downloadUrl;
        this.launcher = launcher;
        this.modpack = modpack;
    }

    /** Pure, so it is unit-tested. */
    static Variant variant(Launcher launcher, boolean modpack) {
        boolean managed = launcherName(launcher) != null;
        if (managed) return modpack ? Variant.PACK_MANAGED : Variant.MOD_MANAGED;
        return modpack ? Variant.PACK_MANUAL : Variant.MOD_MANUAL;
    }

    /**
     * The launcher's own name, for the launchers whose Update button these steps point at; {@code null}
     * for the rest. A brand name, so not translated. MultiMC is left out: it can't update a pack.
     */
    static String launcherName(Launcher launcher) {
        return switch (launcher) {
            case CURSEFORGE -> "CurseForge";
            case MODRINTH -> "Modrinth App";
            case PRISM -> "Prism Launcher";
            case ATLAUNCHER -> "ATLauncher";
            case MINECRAFT_LAUNCHER, MULTIMC, UNKNOWN -> null;
        };
    }

    /** The paragraphs for {@code variant}, in order. */
    static List<Component> paragraphs(Variant variant, String launcherName) {
        return switch (variant) {
            case PACK_MANAGED -> List.of(
                Component.translatable(KEY + "intro.modpack", launcherName),
                step(1, "step.close"),
                step(2, "step.pack_update"));
            case MOD_MANAGED -> List.of(
                Component.translatable(KEY + "intro.mod", launcherName),
                step(1, "step.close"),
                step(2, "step.mod_update"));
            case MOD_MANUAL -> List.of(
                step(1, "step.download"),
                step(2, "step.close"),
                step(3, "step.swap"),
                step(4, "step.start"));
            case PACK_MANUAL -> List.of(
                Component.translatable(KEY + "quickest"),
                step(1, "step.download"),
                step(2, "step.close_swap"),
                Component.translatable(KEY + "new_instance"));
        };
    }

    private static Component step(int n, String key) {
        return Component.literal(n + ". ").append(Component.translatable(KEY + key));
    }

    @Override
    protected void init() {
        Variant variant = variant(launcher, modpack);
        int innerWidth = CARD_W - 2 * PAD;

        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component para : paragraphs(variant, launcherName(launcher))) {
            if (!lines.isEmpty()) lines.add(null);
            lines.addAll(font.split(para, innerWidth));
        }
        bodyLines = lines;
        String keptKey = variant == Variant.MOD_MANUAL ? "kept.own_mods" : "kept";
        keptLines = List.copyOf(font.split(Component.translatable(KEY + keptKey), innerWidth));

        int contentH = font.lineHeight + GAP_TITLE + bodyHeight()
                + PARA_GAP + keptLines.size() * LINE_STEP + GAP_BODY + BUTTON_H;
        panelW = CARD_W;
        panelH = PAD + contentH + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(16, (height - panelH) / 2);

        titleY = panelY + PAD;
        bodyY = titleY + font.lineHeight + GAP_TITLE;
        int buttonY = panelY + panelH - PAD - BUTTON_H;
        addButtons(variant.manual(), panelX + PAD, buttonY, innerWidth);
    }

    private void addButtons(boolean manual, int left, int y, int innerWidth) {
        List<Button.Builder> row = new ArrayList<>();
        if (manual) {
            row.add(Button.builder(Component.translatable(KEY + "download"), b -> openDownload()));
            row.add(Button.builder(Component.translatable(KEY + "open_mods"), b -> openModsFolder()));
        }
        row.add(Button.builder(CommonComponents.GUI_BACK, b -> onClose()));

        int count = row.size();
        int buttonW = (innerWidth - BUTTON_GAP * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int x = left + i * (buttonW + BUTTON_GAP);
            // The last button takes the rounding remainder so the row ends flush with the card.
            int w = i == count - 1 ? left + innerWidth - x : buttonW;
            addRenderableWidget(row.get(i).bounds(x, y, w, BUTTON_H).build());
        }
    }

    private int bodyHeight() {
        int h = 0;
        for (FormattedCharSequence line : bodyLines) h += line == null ? PARA_GAP : LINE_STEP;
        return h;
    }

    /** The same update page as "See what's new", behind vanilla's "open this link?" confirmation. */
    private void openDownload() {
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(downloadUrl));
            this.minecraft.setScreen(this);
        }, downloadUrl, true));
    }

    private void openModsFolder() {
        Util.getPlatform().openPath(FMLPaths.MODSDIR.get().toAbsolutePath());
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

        graphics.drawCenteredString(font, this.title, panelX + panelW / 2, titleY, COLOR_TITLE);
        int x = panelX + PAD;
        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            if (line == null) {
                y += PARA_GAP;
                continue;
            }
            graphics.drawString(font, line, x, y, COLOR_BODY, false);
            y += LINE_STEP;
        }
        y += PARA_GAP;
        for (FormattedCharSequence line : keptLines) {
            graphics.drawString(font, line, x, y, COLOR_KEPT, false);
            y += LINE_STEP;
        }
    }

    /** Back and Esc both return to the update prompt. */
    @Override
    public void onClose() {
        this.minecraft.setScreen(previousScreen);
    }
}
