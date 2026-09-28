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

import java.util.function.IntConsumer;

import javax.annotation.Nullable;

/**
 * Modal number pad for a panel cell that is otherwise a stepper. Cmd-clicking a weight opens this
 * prefilled with the current value, so getting from 1 to 40 is a number typed rather than
 * thirty-nine clicks.
 *
 * <p>Structured like {@link PrefabNameScreen} — the other modal these panels open — including its
 * parent handling: a world-space caller passes {@code null} and lands back in the world with its
 * HUD panel still drawn behind, while a screen-space caller passes the panel screen it replaced so
 * the author is not silently dropped out of the menu they were in.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NumberInputScreen extends Screen {

    private final int min;
    private final int max;
    private final int current;
    private final IntConsumer onSubmit;
    @Nullable
    private final Screen parent;

    private EditBox valueField;
    private Button okButton;

    public NumberInputScreen(Component title, int current, int min, int max,
                             IntConsumer onSubmit, @Nullable Screen parent) {
        super(title);
        this.current = current;
        this.min = min;
        this.max = max;
        this.onSubmit = onSubmit;
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int boxW = 120;
        int boxX = (this.width - boxW) / 2;
        int centreY = this.height / 2;

        this.valueField = new EditBox(this.font, boxX, centreY - 10, boxW, 20, this.title);
        this.valueField.setMaxLength(9);
        // Digits only, plus the empty string so the field can be cleared while retyping.
        this.valueField.setFilter(s -> s == null || s.matches("[0-9]*"));
        this.valueField.setResponder(s -> updateValidity());
        this.valueField.setValue(Integer.toString(this.current));
        // Prefill arrives selected, so the first keystroke replaces it rather than appending.
        this.valueField.setHighlightPos(0);
        this.addRenderableWidget(this.valueField);
        this.setInitialFocus(this.valueField);

        int btnW = 100;
        int gap = 10;
        int totalW = btnW * 2 + gap;
        int okX = (this.width - totalW) / 2;
        int cancelX = okX + btnW + gap;
        int btnY = centreY + 20;

        this.okButton = Button.builder(
            Component.translatable("gui.dungeontrain.number_input.ok"),
            b -> submit()
        ).bounds(okX, btnY, btnW, 20).build();
        this.addRenderableWidget(this.okButton);

        this.addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.number_input.cancel"),
            b -> onClose()
        ).bounds(cancelX, btnY, btnW, 20).build());

        updateValidity();
    }

    private void updateValidity() {
        if (this.okButton == null) return;
        this.okButton.active = parsed() != null;
    }

    /** The typed value, or {@code null} when the field is empty or out of range. */
    @Nullable
    private Integer parsed() {
        if (this.valueField == null) return null;
        String text = this.valueField.getValue();
        if (text.isEmpty()) return null;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value < this.min || value > this.max) return null;
        return value;
    }

    private void submit() {
        Integer value = parsed();
        if (value == null) return;
        onClose();
        this.onSubmit.accept(value);
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
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("gui.dungeontrain.number_input.range", this.min, this.max),
            this.width / 2, this.height / 2 - 25, 0xFFAAAAAA);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(this.parent);
    }
}
