package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SetPrefabAnchorPacket;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The prefab anchor's binding screen: a filter box over the server's prefab roster, a short list
 * of matches to click, Bind / Unbind / Cancel. Opened by {@code OpenPrefabAnchorPacket}, answers
 * with {@link SetPrefabAnchorPacket}.
 *
 * <p>Typing a name that is not in the roster is allowed — the server re-validates — so an author
 * can bind to a prefab they are about to create. Layout is the same modal shape as
 * {@link PrefabNameScreen}, which authors already know from saving loot / variant prefabs.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PrefabAnchorScreen extends Screen {

    private static final int BOX_W = 220;
    private static final int ROW_H = 12;
    private static final int MAX_ROWS = 8;

    private final BlockPos pos;
    private final String current;
    private final List<String> names;

    private EditBox nameField;
    private Button bindButton;
    private final List<Button> rowButtons = new ArrayList<>();

    private PrefabAnchorScreen(BlockPos pos, String current, List<String> names) {
        super(Component.translatable("gui.dungeontrain.prefab_anchor.title"));
        this.pos = pos;
        this.current = current;
        this.names = names;
    }

    /** Open over the world (there is no parent menu to return to). */
    public static void open(BlockPos pos, String current, List<String> names) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new PrefabAnchorScreen(pos, current, names));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int boxX = (this.width - BOX_W) / 2;
        int top = this.height / 2 - 80;

        this.nameField = new EditBox(this.font, boxX, top, BOX_W, 20,
            Component.translatable("gui.dungeontrain.prefab_anchor.name_label"));
        this.nameField.setMaxLength(32);
        this.nameField.setFilter(s -> s == null || s.matches("[a-z0-9_]*"));
        this.nameField.setValue(current == null ? "" : current);
        this.nameField.setResponder(s -> refresh());
        this.addRenderableWidget(this.nameField);
        this.setInitialFocus(this.nameField);

        int rowsTop = top + 26;
        for (int i = 0; i < MAX_ROWS; i++) {
            final int row = i;
            Button b = Button.builder(Component.empty(), btn -> pick(row))
                .bounds(boxX, rowsTop + i * ROW_H, BOX_W, ROW_H).build();
            b.visible = false;
            this.rowButtons.add(b);
            this.addRenderableWidget(b);
        }

        int btnW = 70;
        int gap = 5;
        int totalW = btnW * 3 + gap * 2;
        int x0 = (this.width - totalW) / 2;
        int btnY = rowsTop + MAX_ROWS * ROW_H + 10;

        this.bindButton = Button.builder(Component.translatable("gui.dungeontrain.prefab_anchor.bind"),
            b -> submit(this.nameField.getValue())).bounds(x0, btnY, btnW, 20).build();
        this.addRenderableWidget(this.bindButton);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.prefab_anchor.unbind"),
            b -> submit("")).bounds(x0 + btnW + gap, btnY, btnW, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.prefab_anchor.cancel"),
            b -> onClose()).bounds(x0 + (btnW + gap) * 2, btnY, btnW, 20).build());

        refresh();
    }

    private List<String> matches() {
        String q = this.nameField == null ? "" : this.nameField.getValue().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (q.isEmpty() || n.contains(q)) out.add(n);
            if (out.size() >= MAX_ROWS) break;
        }
        return out;
    }

    private void refresh() {
        List<String> shown = matches();
        for (int i = 0; i < rowButtons.size(); i++) {
            Button b = rowButtons.get(i);
            boolean has = i < shown.size();
            b.visible = has;
            b.setMessage(has ? Component.literal(shown.get(i)) : Component.empty());
        }
        if (bindButton != null) bindButton.active = !this.nameField.getValue().isEmpty();
    }

    private void pick(int row) {
        List<String> shown = matches();
        if (row >= shown.size()) return;
        this.nameField.setValue(shown.get(row));
        refresh();
    }

    private void submit(String name) {
        DungeonTrainNet.sendToServer(new SetPrefabAnchorPacket(pos, name));
        onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)
            && this.bindButton != null && this.bindButton.active) {
            submit(this.nameField.getValue());
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 100, 0xFFFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(null);
    }
}
