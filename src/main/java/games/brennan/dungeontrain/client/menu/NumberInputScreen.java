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

import games.brennan.dungeontrain.client.menu.number.NumberField;

import javax.annotation.Nullable;

/**
 * Modal number pad for a panel cell that is otherwise a stepper. Cmd-clicking a number opens this
 * prefilled with the current value, so getting from 1 to 40 is a number typed rather than
 * thirty-nine clicks.
 *
 * <p>An all-capable field (a max level, a max fill) opens blank when it is unbounded, and a blank
 * submit sets it back to "all" — the hint line under the title says so.</p>
 *
 * <p>Structured like {@link PrefabNameScreen} — the other modal these panels open — including its
 * parent handling: a world-space caller passes {@code null} and lands back in the world with its
 * HUD panel still drawn behind, while a screen-space caller passes the panel screen it replaced so
 * the author is not silently dropped out of the menu they were in.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NumberInputScreen extends Screen {

    private final NumberField field;
    @Nullable
    private final Screen parent;

    private EditBox valueField;
    private Button okButton;

    /** Transitional: a bare typed value with no stepping. Removed once every caller holds a field. */
    public NumberInputScreen(Component title, int current, int min, int max,
                             java.util.function.IntConsumer onSubmit, @Nullable Screen parent) {
        this(new NumberField(title.getString(), current, min, max, false, () -> { }, () -> { }, onSubmit),
            parent);
    }

    public NumberInputScreen(NumberField field, @Nullable Screen parent) {
        super(Component.literal(field.title()));
        this.field = field;
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
        this.valueField.setValue(this.field.typedText());
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

    /** The typed value, or {@code null} when it can't be submitted. */
    @Nullable
    private Integer parsed() {
        if (this.valueField == null) return null;
        return this.field.parse(this.valueField.getValue());
    }

    private void submit() {
        Integer value = parsed();
        if (value == null) return;
        onClose();
        this.field.set().accept(value);
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
        // Text after super.render: Screen.render draws the (blurred) background itself, so text
        // drawn before it is blurred away along with the world behind.
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("gui.dungeontrain.number_input.range", this.field.min(), this.field.max()),
            this.width / 2, this.height / 2 - 25, 0xFFAAAAAA);
        if (this.field.allowAll()) {
            g.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.number_input.all_hint"),
                this.width / 2, this.height / 2 + 48, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(this.parent);
    }
}
