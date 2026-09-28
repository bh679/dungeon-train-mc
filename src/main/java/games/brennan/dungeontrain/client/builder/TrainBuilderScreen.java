package games.brennan.dungeontrain.client.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.DevQuickWorldHandler;
import games.brennan.dungeontrain.client.EditorAutoOpenHandler;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;
import games.brennan.dungeontrain.editor.BuilderModeCategory;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.net.BuilderSwitchPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Train Builder picker: four image tiles on the left, and on the right what the picked one
 * is — its name, its picture, a sentence or two, and a <b>Go here</b> that opens a fresh flat
 * world for that kind of building work.
 *
 * <p>The same shape as the editor's Nav tab ({@code EditorNavPane}), so the front door and the
 * in-editor switch read as one thing. Clicking a tile only picks it; nothing launches until
 * Go here. Tiles have no hover state — only the chosen one is lit — so the cursor passing over
 * the grid never looks like the choice moving. Geometry is {@link BuilderPickerLayout}.</p>
 *
 * <p>This is the friendly front door for both title-screen tools. The slot says <b>Train
 * Editor</b> normally and <b>Train Builder</b> while Shift is held (see
 * {@code TrainBuilderMenuButton}); either way you pick what you are building here first, and only
 * then does a world get made. The tiles mean the same four things on both paths — the Editor route
 * resolves them through {@link BuilderModeCategory} and lands in that editor category, the Builder
 * route stamps that mode's world.</p>
 *
 * <p>The four builder editors are a follow-up task. Today each tile creates the superflat
 * creative world the editor will live in and posts a "coming soon" line for that mode once the
 * world is up, so the world-launch plumbing is testable now and the follow-up only has to
 * replace the stub dispatch in {@link EditorAutoOpenHandler}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TrainBuilderScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TITLE_TOP = 20;
    private static final int BODY_TOP_PADDING = 16;
    private static final int BODY_BOTTOM_MARGIN = 12;
    /** The Nav tab's text metrics, so the two descriptions set the same. */
    private static final int LINE_H = 10;
    private static final int TEXT_PAD = 3;
    private static final int DESCRIPTION = 0xFFDDDDDD;
    private static final int PREVIEW_BACKDROP = 0xFF000000;
    private static final int PREVIEW_OUTLINE = 0xFF000000;
    private static final int HEADER_TEXT = 0xFFFFFF;

    private final Screen lastScreen;
    private final Launch launch;
    /** Probed once per mode per screen rather than per frame; a resource reload rebuilds the screen. */
    private final Map<BuilderMode, Boolean> artAvailable = new EnumMap<>(BuilderMode.class);
    /** The picked tile. Survives {@link #rebuildWidgets()} — that is how a click re-lights the grid. */
    private BuilderMode selected = BuilderMode.values()[0];
    private BuilderPickerLayout layout;

    /** What a tile click does. One branch each, so a flavour can't half-apply. */
    private enum Launch {
        /** Make a fresh Train Builder world and stamp the picked mode into it. */
        NEW_BUILDER_WORLD,
        /** Re-shape the builder world you're already standing in. */
        SWITCH_THIS_WORLD,
        /** Make a fresh Train Editor world and open the picked mode's editor category. */
        EDITOR_WORLD
    }

    public TrainBuilderScreen(Screen lastScreen) {
        this(lastScreen, Launch.NEW_BUILDER_WORLD);
    }

    private TrainBuilderScreen(Screen lastScreen, Launch launch) {
        super(launch == Launch.EDITOR_WORLD
                ? Component.translatable("gui.dungeontrain.editor_button")
                : Component.translatable("gui.dungeontrain.builder.title"));
        this.lastScreen = lastScreen;
        this.launch = launch;
    }

    /**
     * The same picker, reached from the builder pause menu's <b>Open</b>.
     *
     * <p>Identical grid, art and labels — only the tile action differs: instead of creating
     * another world, it asks the server to re-stamp the one you're standing in. The server owns
     * the unsaved-changes decision and may answer with a confirmation prompt instead.</p>
     */
    public static TrainBuilderScreen forSwitch(Screen lastScreen) {
        return new TrainBuilderScreen(lastScreen, Launch.SWITCH_THIS_WORLD);
    }

    /**
     * The same picker, reached from the title screen's <b>Train Editor</b> button.
     *
     * <p>Identical grid, art and labels — only the title and the tile action differ: instead of
     * building a Train Builder world, it creates the editor's creative world and opens the
     * technical editor on the category that tile stands for.</p>
     */
    public static TrainBuilderScreen forEditor(Screen lastScreen) {
        return new TrainBuilderScreen(lastScreen, Launch.EDITOR_WORLD);
    }

    @Override
    protected void init() {
        int topY = TITLE_TOP + this.font.lineHeight + BODY_TOP_PADDING;

        layout = BuilderPickerLayout.of(this.width, this.height, topY, this.height - BODY_BOTTOM_MARGIN);

        BuilderMode[] modes = BuilderMode.values();
        List<BuilderPickerLayout.Rect> cells = layout.tiles();
        for (int i = 0; i < modes.length && i < cells.size(); i++) {
            BuilderMode mode = modes[i];
            BuilderPickerLayout.Rect cell = cells.get(i);
            this.addRenderableWidget(BuilderTileButton.pickerTile(
                    cell.x(), cell.y(), cell.w(), cell.h(), mode, mode == selected, b -> select(mode)));
        }

        BuilderPickerLayout.Rect go = layout.go();
        this.addRenderableWidget(Button.builder(
                        Component.translatable(EditorScreenLang.NAV_GO_HERE), b -> launch(selected))
                .bounds(go.x(), go.y(), go.w(), go.h())
                .build());

        // Back shares Go here's row, under the tiles: the two ways out, side by side.
        BuilderPickerLayout.Rect back = layout.back();
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .bounds(back.x(), back.y(), back.w(), back.h())
                .build());
    }

    /** A tile click: re-light the grid around the new choice; the detail column follows on render. */
    private void select(BuilderMode mode) {
        if (mode == selected) return;
        selected = mode;
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_TOP, 0xFFFFFF);
        if (layout != null) {
            drawDetail(g);
        }
    }

    /** The right column: name, picture, description. The Go here button is a widget and draws itself. */
    private void drawDetail(GuiGraphics g) {
        BuilderPickerLayout.Rect h = layout.header();
        String name = Component.translatable(selected.labelKey()).getString();
        g.drawString(this.font, this.font.plainSubstrByWidth(name, h.w() - 4), h.x() + 2,
                h.y() + (h.h() - this.font.lineHeight) / 2, HEADER_TEXT, true);

        BuilderPickerLayout.Rect p = layout.preview();
        g.fill(p.x(), p.y(), p.right(), p.bottom(), PREVIEW_BACKDROP);
        BuilderTileArt.render(g, selected, available(selected), p.x(), p.y(), p.w(), p.h(), 1.0F);
        g.renderOutline(p.x(), p.y(), p.w(), p.h(), PREVIEW_OUTLINE);

        BuilderPickerLayout.Rect d = layout.description();
        int textW = Math.max(0, d.w() - TEXT_PAD * 2);
        List<FormattedCharSequence> lines = this.font.split(Component.translatable(selected.descriptionKey()), textW);
        int y = d.y() + TEXT_PAD;
        for (FormattedCharSequence line : lines) {
            if (y + LINE_H > d.bottom()) break;
            g.drawString(this.font, line, d.x() + TEXT_PAD, y, DESCRIPTION, false);
            y += LINE_H;
        }
    }

    private boolean available(BuilderMode mode) {
        return artAvailable.computeIfAbsent(mode, BuilderTileArt::isAvailable);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void launch(BuilderMode mode) {
        switch (launch) {
            case SWITCH_THIS_WORLD -> {
                LOGGER.info("TrainBuilder: '{}' selected — asking the server to re-stamp this world", mode.id());
                Minecraft.getInstance().setScreen(null);
                DungeonTrainNet.sendToServer(new BuilderSwitchPacket(mode.id(), false));
            }
            case EDITOR_WORLD -> {
                EditorCategory category = BuilderModeCategory.of(mode);
                LOGGER.info("TrainBuilder: '{}' selected — launching editor world on category '{}'",
                        mode.id(), category.id());
                // Same one-shot the builder path uses: force source-tree write-through on for this
                // session so anything saved lands in the working tree on a dev checkout.
                EditorDevMode.queueOnForNextStart();
                EditorAutoOpenHandler.queueAutoOpen(category);
                DevQuickWorldHandler.launchEditorWorld(this.lastScreen);
            }
            case NEW_BUILDER_WORLD -> {
                LOGGER.info("TrainBuilder: '{}' selected — launching flat builder world", mode.id());
                // Same one-shot as the editor button: force source-tree write-through on for this
                // session so anything the builder saves lands in the working tree on a dev checkout.
                EditorDevMode.queueOnForNextStart();
                EditorAutoOpenHandler.queueBuilderSetup(mode);
                DevQuickWorldHandler.launchBuilderWorld(this.lastScreen, mode);
            }
        }
    }
}
