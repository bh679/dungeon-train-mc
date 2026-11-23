package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.SignVariants;
import games.brennan.dungeontrain.net.BlockVariantEditPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits a sign row's text from the Z menu's Sign popup: four lines on the front and four on the
 * back, each side's dye colour and glow. Done sends {@code SET_SIGN_TEXT}; the server writes it into
 * the row (and, with shared text, into every shared sign row of the cell).
 *
 * <p>Lines are held to the narrowest sign the text can end up on ({@link #lineWidth}), measured
 * with the game font as vanilla's sign editor does. The wax is carried through unchanged; it has
 * its own toggle in the popup.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SignTextScreen extends Screen {

    private static final int BOX_W = 180;
    private static final int ROW_H = 22;

    private final String variantId;
    private final BlockPos localPos;
    private final int row;
    private final int lineWidth;
    @Nullable
    private final Screen parent;

    /** Working copy of both sides; index 0 = front, 1 = back. */
    private final String[][] lines = new String[2][SignVariants.LINES];
    private final DyeColor[] colours = new DyeColor[2];
    private final boolean[] glow = new boolean[2];
    private final boolean waxed;
    private int side;

    private final List<EditBox> boxes = new ArrayList<>();

    /**
     * @param lineWidth   line width in font pixels ({@link SignVariants#lineWidth})
     * @param sharedCount how many sign rows this edit writes to (1 = just this one)
     * @param parent      where to return, or {@code null} for the world (a world-space menu)
     */
    public SignTextScreen(String variantId, BlockPos localPos, int row, SignVariants.Text text,
                          int lineWidth, int sharedCount, @Nullable Screen parent) {
        super(Component.literal(sharedCount > 1
            ? MenuLang.t("block_variant.sign_screen_title_shared", sharedCount)
            : MenuLang.t("block_variant.sign_screen_title")));
        this.variantId = variantId;
        this.localPos = localPos;
        this.row = row;
        this.lineWidth = lineWidth;
        this.parent = parent;
        load(0, text.front());
        load(1, text.back());
        this.waxed = text.waxed();
    }

    private void load(int s, SignVariants.Side from) {
        for (int i = 0; i < SignVariants.LINES; i++) lines[s][i] = from.lines().get(i);
        colours[s] = from.color();
        glow[s] = from.glow();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        boxes.clear();
        int cx = this.width / 2;
        int top = this.height / 2 - 70;

        int tabW = BOX_W / 2;
        addRenderableWidget(Button.builder(sideLabel(0), b -> switchSide(0))
            .bounds(cx - BOX_W / 2, top, tabW, 20).build());
        addRenderableWidget(Button.builder(sideLabel(1), b -> switchSide(1))
            .bounds(cx - BOX_W / 2 + tabW, top, tabW, 20).build());

        for (int i = 0; i < SignVariants.LINES; i++) {
            final int line = i;
            EditBox box = new EditBox(this.font, cx - BOX_W / 2, top + 26 + i * ROW_H, BOX_W, 18,
                Component.literal(MenuLang.t("block_variant.sign_screen_line", i + 1)));
            box.setMaxLength(SignVariants.MAX_LINE_CHARS);
            // Value first, filter after: a line already wider than this sign allows (written for a
            // wider sign, or by hand) must load intact, or Done would quietly erase it.
            box.setValue(lines[side][i]);
            int loaded = this.font.width(lines[side][i]);
            box.setFilter(v -> fits(v, loaded));
            box.setResponder(v -> lines[side][line] = v);
            boxes.add(box);
            addRenderableWidget(box);
        }
        setInitialFocus(boxes.get(0));

        int optY = top + 26 + SignVariants.LINES * ROW_H + 4;
        addRenderableWidget(Button.builder(colourLabel(), b -> {
                colours[side] = DyeColor.byId((colours[side].getId() + 1) % DyeColor.values().length);
                b.setMessage(colourLabel());
            }).bounds(cx - BOX_W / 2, optY, BOX_W / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(glowLabel(), b -> {
                glow[side] = !glow[side];
                b.setMessage(glowLabel());
            }).bounds(cx + 2, optY, BOX_W / 2 - 2, 20).build());

        int btnY = optY + 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> submit())
            .bounds(cx - BOX_W / 2, btnY, BOX_W / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(cx + 2, btnY, BOX_W / 2 - 2, 20).build());
    }

    /**
     * A line fits when it is short enough to draw on the narrowest sign it can land on. A line that
     * loaded wider than that ({@code loadedWidth}) may still be edited as long as it doesn't grow.
     */
    private boolean fits(String s, int loadedWidth) {
        if (s == null || !SignVariants.clampLine(s).equals(s)) return false;
        return this.font.width(s) <= Math.max(lineWidth, loadedWidth);
    }

    private Component sideLabel(int s) {
        String label = MenuLang.t(s == 0 ? "block_variant.sign_screen_front" : "block_variant.sign_screen_back");
        return Component.literal(s == side ? "[" + label + "]" : label);
    }

    private Component colourLabel() {
        return Component.literal(MenuLang.t("block_variant.sign_screen_colour",
            Component.translatable("color.minecraft." + colours[side].getName()).getString()));
    }

    private Component glowLabel() {
        return Component.literal(MenuLang.t("block_variant.sign_screen_glow",
            MenuLang.t(glow[side] ? "block_variant.active_on" : "block_variant.active_off")));
    }

    private void switchSide(int s) {
        if (s == side) return;
        side = s;
        rebuildWidgets();
    }

    private SignVariants.Side sideOf(int s) {
        return new SignVariants.Side(List.of(lines[s]), colours[s], glow[s]);
    }

    private void submit() {
        SignVariants.Text text = new SignVariants.Text(sideOf(0), sideOf(1), waxed);
        DungeonTrainNet.sendToServer(new BlockVariantEditPacket(
            BlockVariantEditPacket.Op.SET_SIGN_TEXT, variantId, localPos, row, SignVariants.toWire(text), 0));
        onClose();
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
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 88, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }
}
