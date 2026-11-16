package games.brennan.dungeontrain.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * Two-box twin of {@link NumberInputScreen} for a loot entry's stack size: the count cell's
 * cmd-click opens this with the entry's min and max, the only place the min can be edited.
 * Clicking the cell still steps the max alone.
 *
 * <p>Done stays disabled until both boxes hold numbers, the max is within {@code [1, max]} and
 * the min is between 1 and the max; a hint line says when the min is the problem. Parent
 * handling matches {@link NumberInputScreen}: a world-space caller passes {@code null}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CountRangeInputScreen extends Screen {

    /** Receives the submitted range. */
    @FunctionalInterface
    public interface Submit {
        void accept(int min, int max);
    }

    private final int initialMin;
    private final int initialMax;
    private final int limit;
    private final Submit onSubmit;
    @Nullable
    private final Screen parent;

    private EditBox minField;
    private EditBox maxField;
    private Button okButton;

    public CountRangeInputScreen(Component title, int min, int max, int limit, Submit onSubmit,
                                 @Nullable Screen parent) {
        super(title);
        this.initialMin = min;
        this.initialMax = max;
        this.limit = limit;
        this.onSubmit = onSubmit;
        this.parent = parent;
    }

    /**
     * The range {@code minText}–{@code maxText} stands for, as {@code {min, max}}, or null when it
     * can't be submitted. Package-visible for tests.
     */
    @Nullable
    static int[] parse(String minText, String maxText, int limit) {
        Integer min = parseInt(minText);
        Integer max = parseInt(maxText);
        if (min == null || max == null) return null;
        if (max < 1 || max > limit) return null;
        if (min < 1 || min > max) return null;
        return new int[] {min, max};
    }

    @Nullable
    private static Integer parseInt(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return null;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int boxW = 50;
        int labelW = 30;
        int gap = 16;
        int pairW = labelW + boxW;
        int left = (this.width - (pairW * 2 + gap)) / 2;
        int centreY = this.height / 2;

        this.minField = numberBox(left + labelW, centreY - 10, boxW, this.initialMin,
            Component.translatable("gui.dungeontrain.number_input.count_min"));
        this.maxField = numberBox(left + pairW + gap + labelW, centreY - 10, boxW, this.initialMax,
            Component.translatable("gui.dungeontrain.number_input.count_max"));
        this.setInitialFocus(this.minField);

        int btnW = 100;
        int okX = (this.width - (btnW * 2 + 10)) / 2;
        int btnY = centreY + 20;
        this.okButton = Button.builder(
            Component.translatable("gui.dungeontrain.number_input.ok"),
            b -> submit()
        ).bounds(okX, btnY, btnW, 20).build();
        this.addRenderableWidget(this.okButton);
        this.addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.number_input.cancel"),
            b -> onClose()
        ).bounds(okX + btnW + 10, btnY, btnW, 20).build());

        updateValidity();
    }

    private EditBox numberBox(int x, int y, int w, int value, Component label) {
        EditBox box = new EditBox(this.font, x, y, w, 20, label);
        box.setMaxLength(3);
        // Digits only, plus the empty string so the box can be cleared while retyping.
        box.setFilter(s -> s == null || s.matches("[0-9]*"));
        box.setResponder(s -> updateValidity());
        box.setValue(Integer.toString(value));
        // Prefill arrives selected, so the first keystroke replaces it rather than appending.
        box.setHighlightPos(0);
        this.addRenderableWidget(box);
        return box;
    }

    @Nullable
    private int[] parsed() {
        if (this.minField == null || this.maxField == null) return null;
        return parse(this.minField.getValue(), this.maxField.getValue(), this.limit);
    }

    private void updateValidity() {
        if (this.okButton == null) return;
        this.okButton.active = parsed() != null;
    }

    /** True when both boxes are numbers and the max is fine, so only the min is out of range. */
    private boolean minAboveMax() {
        Integer min = parseInt(this.minField.getValue());
        Integer max = parseInt(this.maxField.getValue());
        return min != null && max != null && max >= 1 && max <= this.limit && min > max;
    }

    private void submit() {
        int[] range = parsed();
        if (range == null) return;
        onClose();
        this.onSubmit.accept(range[0], range[1]);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Text after super.render: Screen.render draws the (blurred) background itself.
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.drawCenteredString(this.font, this.title, cx, cy - 40, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("gui.dungeontrain.number_input.range", 1, this.limit),
            cx, cy - 25, 0xFFAAAAAA);
        g.drawString(this.font, Component.translatable("gui.dungeontrain.number_input.count_min"),
            this.minField.getX() - 28, cy - 4, 0xFFAAAAAA);
        g.drawString(this.font, Component.translatable("gui.dungeontrain.number_input.count_max"),
            this.maxField.getX() - 28, cy - 4, 0xFFAAAAAA);
        if (minAboveMax()) {
            g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.number_input.count_range_invalid"),
                cx, cy + 48, 0xFFFF6060);
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(this.parent);
    }
}
