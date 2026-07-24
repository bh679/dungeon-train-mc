package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Editor / display-scale settings for Dungeon Train — the <b>All Displays</b>, <b>Worldspace</b>, and
 * <b>HUD</b> scale steppers that also live in the in-world X-menu {@code OptionsMenuScreen}. Opened as a
 * sub-screen from {@link DungeonTrainClientOptionsScreen} ("Editor Settings…"), so these controls are
 * reachable from the vanilla Options screen too.
 *
 * <p>A second <em>view</em> over the same {@link ClientDisplayConfig} scale accessors — no new config —
 * so it stays in lock-step with the X-menu. Each ± press re-inits the screen to refresh its value
 * labels (the same pattern the X-menu uses per tick).</p>
 */
public final class DungeonTrainEditorSettingsScreen extends Screen {

    private static final int ROW_W = 210;
    private static final int ROW_H = 20;
    private static final int STEP_W = 22;
    private static final int GAP = 4;
    private static final int ROW_GAP = 24;

    private final Screen parent;

    public DungeonTrainEditorSettingsScreen(Screen parent) {
        super(Component.literal("Editor Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 3;

        y = scaleRow(cx, y, "All Displays", "Master multiplier applied on top of both the Worldspace and HUD scales.",
                ClientDisplayConfig::getAllScale, ClientDisplayConfig::setAllScale);
        y = scaleRow(cx, y, "Worldspace", "Scale for the X-menu, editor menus and in-world debug labels.",
                ClientDisplayConfig::getWorldspaceChannel, ClientDisplayConfig::setWorldspaceChannel);
        y = scaleRow(cx, y, "HUD", "Scale for the top-left version line and the editor status bar.",
                ClientDisplayConfig::getHudChannel, ClientDisplayConfig::setHudChannel);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(cx - 100, y + 6, 200, ROW_H).build());
    }

    /** A {@code [label: value] [-] [+]} row for one display-scale channel; returns the next row's y. */
    private int scaleRow(int cx, int y, String name, String description, DoubleSupplier get, DoubleConsumer set) {
        int left = cx - ROW_W / 2;
        Tooltip tip = Tooltip.create(Component.literal(description));
        StringWidget label = new StringWidget(left, y, ROW_W - 2 * (STEP_W + GAP), ROW_H,
                scaleLabel(name, get.getAsDouble()), this.font);
        label.setTooltip(tip);
        addRenderableWidget(label);
        Button minus = Button.builder(Component.literal("-"),
                        b -> { set.accept(get.getAsDouble() - ClientDisplayConfig.STEP); rebuildWidgets(); })
                .bounds(left + ROW_W - 2 * STEP_W - GAP, y, STEP_W, ROW_H).build();
        minus.setTooltip(tip);
        addRenderableWidget(minus);
        Button plus = Button.builder(Component.literal("+"),
                        b -> { set.accept(get.getAsDouble() + ClientDisplayConfig.STEP); rebuildWidgets(); })
                .bounds(left + ROW_W - STEP_W, y, STEP_W, ROW_H).build();
        plus.setTooltip(tip);
        addRenderableWidget(plus);
        return y + ROW_GAP;
    }

    private static Component scaleLabel(String name, double value) {
        return Component.literal(name + ": " + String.format(Locale.ROOT, "%.1f", value));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
