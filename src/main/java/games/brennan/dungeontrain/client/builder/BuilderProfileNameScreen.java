package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderNewOptions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Ask for one template name.
 *
 * <p>Used by both naming answers to a name collision, which is why the question is a parameter: the
 * player is naming a different build in each case, and a screen that did not say which would be
 * asking them to guess.</p>
 *
 * <p>Validity is {@link BuilderNewOptions#isValidName}, the same rule the New screen enforces, so a
 * name that would be refused here is exactly one that could never have been typed there.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileNameScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;

    private final Screen lastScreen;
    private final Component prompt;
    private final String suggestion;
    private final Consumer<String> onConfirm;

    private EditBox nameField;
    private Button confirmButton;
    private String name;

    public BuilderProfileNameScreen(Screen lastScreen, Component prompt, String suggestion,
                                     Consumer<String> onConfirm) {
        super(prompt);
        this.lastScreen = lastScreen;
        this.prompt = prompt;
        this.suggestion = suggestion == null ? "" : suggestion;
        this.onConfirm = onConfirm;
        this.name = this.suggestion;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - FIELD_WIDTH / 2;
        int y = this.height / 2 - 10;

        this.nameField = new EditBox(this.font, x, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.new.name"));
        this.nameField.setMaxLength(32);
        this.nameField.setValue(name);
        // Lower-cased as it is typed, exactly as the New screen does it — a template id is lower case,
        // and letting a capital through here would only fail validation a moment later.
        this.nameField.setResponder(value -> {
            this.name = value.toLowerCase(Locale.ROOT);
            refreshConfirmEnabled();
        });
        addRenderableWidget(this.nameField);
        setInitialFocus(this.nameField);
        y += ROW_HEIGHT + ROW_GAP * 2;

        this.confirmButton = Button.builder(CommonComponents.GUI_DONE, b -> confirm())
                .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT).build();
        addRenderableWidget(this.confirmButton);
        y += ROW_HEIGHT + ROW_GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT).build());

        refreshConfirmEnabled();
    }

    private void refreshConfirmEnabled() {
        if (this.confirmButton != null) {
            this.confirmButton.active = BuilderNewOptions.isValidName(name);
        }
    }

    private void confirm() {
        if (!BuilderNewOptions.isValidName(name)) return;
        this.minecraft.setScreen(lastScreen);
        onConfirm.accept(name);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter confirms, so a name can be typed and committed without reaching for the mouse.
        if (keyCode == 257 || keyCode == 335) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, prompt, this.width / 2, this.height / 2 - 40, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
