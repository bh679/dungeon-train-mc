package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SaveBlockVariantPrefabPacket;
import games.brennan.dungeontrain.net.SaveLootPrefabPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Pattern;

/**
 * Modal screen opened by the V-key / C-key menu's Save button. Captures a
 * prefab name in a vanilla {@link EditBox}, validates it against the same
 * {@code ^[a-z0-9_]{1,32}$} pattern the server enforces, and dispatches the
 * appropriate save packet on submit.
 *
 * <p>Vanilla {@code Screen.removed()} restores the previous screen — when
 * this closes the player returns to the world with the V/C menu still
 * visible (it's a HUD overlay, not a real screen).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabNameScreen extends Screen {

    public enum Kind {
        VARIANT,
        LOOT
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private final Kind kind;
    private final BlockPos localPos;

    private EditBox nameField;
    private Button saveButton;
    private Component errorMessage = Component.empty();

    public PrefabNameScreen(Kind kind, BlockPos localPos) {
        super(titleFor(kind));
        this.kind = kind;
        this.localPos = localPos;
    }

    private static Component titleFor(Kind kind) {
        return switch (kind) {
            case VARIANT -> Component.translatable("gui.dungeontrain.prefab_save.title.variant");
            case LOOT -> Component.translatable("gui.dungeontrain.prefab_save.title.loot");
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int boxW = 220;
        int boxX = (this.width - boxW) / 2;
        int centreY = this.height / 2;

        this.nameField = new EditBox(this.font, boxX, centreY - 10, boxW, 20,
            Component.translatable("gui.dungeontrain.prefab_save.name_label"));
        this.nameField.setMaxLength(32);
        this.nameField.setFilter(s -> s == null || s.matches("[a-z0-9_]*"));
        this.nameField.setResponder(s -> updateValidity());
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        int btnW = 100;
        int gap = 10;
        int totalW = btnW * 2 + gap;
        int saveX = (this.width - totalW) / 2;
        int cancelX = saveX + btnW + gap;
        int btnY = centreY + 20;

        this.saveButton = Button.builder(
            Component.translatable("gui.dungeontrain.prefab_save.save"),
            b -> submit()
        ).bounds(saveX, btnY, btnW, 20).build();
        this.addRenderableWidget(this.saveButton);

        this.addRenderableWidget(Button.builder(
            Component.translatable("gui.dungeontrain.prefab_save.cancel"),
            b -> onClose()
        ).bounds(cancelX, btnY, btnW, 20).build());

        updateValidity();
    }

    private void updateValidity() {
        if (this.saveButton == null || this.nameField == null) return;
        String text = this.nameField.getValue();
        boolean valid = NAME_PATTERN.matcher(text).matches();
        this.saveButton.active = valid;
        this.errorMessage = valid
            ? Component.empty()
            : (text.isEmpty()
                ? Component.empty()
                : Component.translatable("gui.dungeontrain.prefab_save.name_label"));
    }

    private void submit() {
        String name = this.nameField.getValue();
        if (!NAME_PATTERN.matcher(name).matches()) return;
        switch (this.kind) {
            case VARIANT -> DungeonTrainNet.sendToServer(
                new SaveBlockVariantPrefabPacket(this.localPos, name));
            case LOOT -> DungeonTrainNet.sendToServer(
                new SaveLootPrefabPacket(this.localPos, name));
        }
        onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
            && this.saveButton != null && this.saveButton.active) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        int titleY = this.height / 2 - 50;
        g.drawCenteredString(this.font, this.title, this.width / 2, titleY, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("gui.dungeontrain.prefab_save.name_label"),
            this.width / 2, this.height / 2 - 25, 0xFFAAAAAA);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(null);
    }
}
