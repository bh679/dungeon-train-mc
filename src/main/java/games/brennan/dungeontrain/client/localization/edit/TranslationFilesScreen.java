package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Export / import for translating outside the game.
 *
 * <p>Minecraft has no file picker and no drag-and-drop, so this follows the pattern
 * {@code UserContentImporter} and the package menu already use: buttons that write to (or scan)
 * a known folder, plus an "open folder" that hands the player off to their OS file manager.</p>
 */
public final class TranslationFilesScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MARGIN = 16;
    private static final int ROW_W = 240;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 24;
    private static final int LABEL_COLOUR = 0xFFA0A0A0;
    private static final int OK_COLOUR = 0xFF7FDD7F;
    private static final int ERROR_COLOUR = 0xFFDD7F7F;

    private final Screen parent;
    private final String locale;

    private Component status = CommonComponents.EMPTY;
    private boolean statusIsError;

    public TranslationFilesScreen(Screen parent, String locale) {
        super(Component.translatable("gui.dungeontrain.translate.files.title"));
        this.parent = parent;
        this.locale = locale;
    }

    @Override
    protected void init() {
        int left = width / 2 - ROW_W / 2;
        int y = height / 3;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.files.export"), b -> runExport())
            .bounds(left, y, ROW_W, ROW_H).build())
            .setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.files.export.tip")));
        y += ROW_GAP;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.files.import"), b -> runImport())
            .bounds(left, y, ROW_W, ROW_H).build())
            .setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.dungeontrain.translate.files.import.tip")));
        y += ROW_GAP;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.translate.files.open"), b -> openFolder())
            .bounds(left, y, ROW_W, ROW_H).build());
        y += ROW_GAP + ROW_GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(left, y, ROW_W, ROW_H).build());
    }

    private void runExport() {
        TranslationExporter.Result result = TranslationExporter.export(locale);
        if (result.ok()) {
            setStatus(Component.translatable("gui.dungeontrain.translate.files.exported",
                result.uiRows() + result.siblingRows(), result.books()), false);
        } else {
            setStatus(Component.translatable("gui.dungeontrain.translate.files.failed"), true);
        }
    }

    private void runImport() {
        TranslationImporter.Result result = TranslationImporter.importAll(locale);
        if (!result.ok()) {
            setStatus(Component.translatable("gui.dungeontrain.translate.files.failed"), true);
            return;
        }
        if (result.changed() == 0) {
            // Distinct from an error on purpose: the usual cause is an empty import folder, and
            // "0 changes" plus the folder path is the answer to "why did nothing happen".
            setStatus(Component.translatable("gui.dungeontrain.translate.files.nothing"), false);
            return;
        }
        setStatus(Component.translatable("gui.dungeontrain.translate.files.imported",
            result.langChanged(), result.bookChanged()), false);
    }

    /**
     * Open the translations folder in the OS file manager, creating both halves first so the
     * player lands somewhere that exists and can see where their files should go.
     */
    private void openFolder() {
        try {
            Path root = TranslationOverrideStore.root();
            Files.createDirectories(TranslationExporter.directoryFor(locale));
            Files.createDirectories(TranslationImporter.directoryFor(locale));
            Util.getPlatform().openUri(root.toUri());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not open the folder — {}", e.toString());
            setStatus(Component.translatable("gui.dungeontrain.translate.files.failed"), true);
        }
    }

    private void setStatus(Component message, boolean error) {
        this.status = message;
        this.statusIsError = error;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, MARGIN, 0xFFFFFFFF);
        g.drawCenteredString(font,
            Component.translatable("gui.dungeontrain.translate.files.subtitle", locale),
            width / 2, MARGIN + font.lineHeight + 2, LABEL_COLOUR);
        if (status != CommonComponents.EMPTY) {
            g.drawCenteredString(font, status, width / 2, height / 3 + ROW_GAP * 3,
                statusIsError ? ERROR_COLOUR : OK_COLOUR);
        }
    }

    @Override
    public void onClose() {
        if (parent instanceof TranslationScreen list) {
            list.onEditsChanged();
        }
        minecraft.setScreen(parent);
    }
}
