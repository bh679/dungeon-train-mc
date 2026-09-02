package games.brennan.dungeontrain.client.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.DevQuickWorldHandler;
import games.brennan.dungeontrain.client.EditorAutoOpenHandler;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

/**
 * The Train Builder picker: four image tiles, two per row, each opening a fresh flat world for
 * one kind of building work.
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
    private static final int GRID_TOP_PADDING = 16;
    private static final int BACK_BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_HEIGHT = 20;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;

    private final Screen lastScreen;
    private final Launch launch;

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
        int backY = this.height - BACK_BUTTON_BOTTOM_MARGIN;
        int topY = TITLE_TOP + this.font.lineHeight + GRID_TOP_PADDING;

        BuilderGridLayout layout = BuilderGridLayout.of(this.width, this.height, topY, backY - GRID_TOP_PADDING);

        BuilderMode[] modes = BuilderMode.values();
        for (int i = 0; i < modes.length; i++) {
            BuilderMode mode = modes[i];
            this.addRenderableWidget(new BuilderTileButton(
                    layout.xFor(i), layout.yFor(i), layout.tileWidth(), layout.tileHeight(),
                    mode, b -> launch(mode)));
        }

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .bounds((this.width - BACK_BUTTON_WIDTH) / 2, backY, BACK_BUTTON_WIDTH, BACK_BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_TOP, 0xFFFFFF);
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
