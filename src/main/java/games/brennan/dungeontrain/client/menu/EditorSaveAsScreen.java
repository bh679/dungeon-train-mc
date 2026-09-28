package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.editor.EditorTemplateAddress;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorSaveAsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What a Train Editor Save asks when the template ships with the mod: save your version as a new
 * template of your own, or keep it as a local edit over the shipped one.
 *
 * <p>Opened by the server ({@code EditorSaveAsPromptPacket}) rather than by any one Save button,
 * because every Editor Save reaches the server and only the server knows what ships. Worded with
 * the Train Builder's own Save-as keys, so the two tools ask the same question in the same words.</p>
 *
 * <p>The name is checked here only for shape and for being the shipped name itself; whether it is
 * already taken, and each kind's own rules, are the server's to say.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class EditorSaveAsScreen extends Screen {

    /** Lower-case, digits and underscores — the common ground of every editor kind's name rule. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static final int FIELD_WIDTH = 220;
    private static final int ROW_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int BUTTON_WIDTH = 107;

    private final EditorTemplateAddress address;
    private final String displayName;

    private EditBox nameField;
    private Button saveButton;
    private int noteY;
    private int hintY;

    private EditorSaveAsScreen(EditorTemplateAddress address, String displayName) {
        super(Component.translatable("gui.dungeontrain.builder.new.save_title"));
        this.address = address;
        this.displayName = displayName == null || displayName.isEmpty() ? address.name() : displayName;
    }

    /** Replace whatever menu the Save came from with this question. */
    public static void open(EditorTemplateAddress address, String displayName) {
        // The X menu is a world-space overlay rather than a screen; close it first so this replaces
        // it instead of drawing over a menu that is still taking the mouse.
        CommandMenuState.close();
        Minecraft.getInstance().setScreen(new EditorSaveAsScreen(address, displayName));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int x = (this.width - FIELD_WIDTH) / 2;
        int y = this.height / 2 - 40;
        this.noteY = y - 34;

        this.nameField = new EditBox(this.font, x, y, FIELD_WIDTH, ROW_HEIGHT,
            Component.translatable("gui.dungeontrain.builder.new.name"));
        this.nameField.setMaxLength(32);
        this.nameField.setFilter(s -> s == null || s.matches("[a-z0-9_]*"));
        this.nameField.setResponder(s -> refresh());
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);
        y += ROW_HEIGHT + 2;
        this.hintY = y + 2;
        y += 14;

        this.saveButton = Button.builder(Component.translatable("gui.dungeontrain.builder.new.save"),
                b -> saveAsNew())
            .bounds(x, y, BUTTON_WIDTH, ROW_HEIGHT).build();
        this.addRenderableWidget(this.saveButton);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .bounds(x + FIELD_WIDTH - BUTTON_WIDTH, y, BUTTON_WIDTH, ROW_HEIGHT).build());
        y += ROW_HEIGHT + GAP;

        // The other way out, secondary and full width below the pair: it cannot be submitted, and the
        // server says so when it runs.
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.dungeontrain.builder.new.keep_local"), b -> keepLocal())
            .bounds(x, y, FIELD_WIDTH, ROW_HEIGHT).build());

        refresh();
    }

    private void refresh() {
        if (this.saveButton != null) this.saveButton.active = isUsableName(currentName());
    }

    private String currentName() {
        return this.nameField == null ? "" : this.nameField.getValue();
    }

    private boolean isUsableName(String name) {
        return NAME_PATTERN.matcher(name).matches() && !name.equals(address.name());
    }

    private void saveAsNew() {
        String name = currentName();
        if (!isUsableName(name)) return;
        DungeonTrainNet.sendToServer(new EditorSaveAsPacket(address, EditorSaveAsPacket.Choice.NEW, name));
        Minecraft.getInstance().setScreen(null);
    }

    private void keepLocal() {
        DungeonTrainNet.sendToServer(new EditorSaveAsPacket(address, EditorSaveAsPacket.Choice.LOCAL, ""));
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
            && this.saveButton != null && this.saveButton.active) {
            saveAsNew();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.noteY - 16, 0xFFFFFF);

        // Why Save stopped here — wrapped, since a template's name can be long and this has to read
        // at any GUI scale.
        Component note = Component.translatable("gui.dungeontrain.builder.new.builtin_note", displayName);
        List<FormattedCharSequence> lines = this.font.split(note, Math.min(this.width - 32, FIELD_WIDTH + 100));
        int y = this.noteY;
        for (FormattedCharSequence line : lines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, 0xA0A0A0);
            y += this.font.lineHeight + 1;
        }

        String name = currentName();
        if (name.equals(address.name())) {
            g.drawCenteredString(this.font, Component.translatable("gui.dungeontrain.builder.new.shipped_name"),
                this.width / 2, this.hintY, 0xFF7070);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
