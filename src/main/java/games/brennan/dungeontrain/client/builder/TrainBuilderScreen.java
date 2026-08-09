package games.brennan.dungeontrain.client.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.DevQuickWorldHandler;
import games.brennan.dungeontrain.client.EditorAutoOpenHandler;
import games.brennan.dungeontrain.editor.EditorDevMode;
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
 * <p>This is the friendly front door that replaced the title screen's Train Editor button. The
 * editor itself is unchanged and still reachable — on a dev build, holding Shift on the title
 * screen swaps the button back to it (see {@code TrainBuilderMenuButton}).</p>
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

    public TrainBuilderScreen(Screen lastScreen) {
        super(Component.translatable("gui.dungeontrain.builder.title"));
        this.lastScreen = lastScreen;
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
        LOGGER.info("TrainBuilder: '{}' selected — launching flat builder world", mode.id());
        // Same one-shot as the editor button: force source-tree write-through on for this
        // session so anything the builder saves lands in the working tree on a dev checkout.
        EditorDevMode.queueOnForNextStart();
        EditorAutoOpenHandler.queueBuilderStub(mode);
        DevQuickWorldHandler.launchBuilderWorld(this.lastScreen, mode);
    }
}
