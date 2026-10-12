package games.brennan.dungeontrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * A collection's album — a slot for everything it asks for: each photo logged so far, one per animal, mob or
 * biome, and an empty frame marked ??? for each still missing. Each photo sits on the paper it was
 * printed on, labelled with the game's own name for what it shows. Opened by clicking the
 * advancement ({@link EarnedPhotos#tryOpen}), earned or still filling, drawn over the advancements
 * screen. Click a print to see it large and click again to go back; a click outside the prints, or
 * Esc, puts the album away.
 */
public final class EarnedPhotoAlbumScreen extends Screen {

    private static final float OVERLAY_Z = 1000F;
    private static final int CELL = 72;
    private static final int LABEL = 12;
    private static final int GAP = 10;
    private static final int TOP = 44;
    private static final int GRID_SAMPLE = 128;
    private static final int LARGE_SAMPLE = 512;
    private static final int SCROLL_STEP = 24;

    private final Screen parent;
    private final List<EarnedPhotos.Entry> entries;
    private final boolean biomes;
    private int scroll;
    private int enlarged = -1;

    public EarnedPhotoAlbumScreen(Screen parent, Component title, List<EarnedPhotos.Entry> entries, boolean biomes) {
        super(title);
        this.parent = parent;
        this.entries = entries;
        this.biomes = biomes;
    }

    @Override
    protected void init() {
        if (parent != null && (parent.width != width || parent.height != height)) {
            parent.resize(minecraft, width, height);
        }
        scroll = Math.min(scroll, maxScroll());
    }

    private int columns() {
        return Math.max(1, Math.min(entries.size(), (width - 2 * GAP) / (CELL + GAP)));
    }

    private int gridLeft() {
        int cols = columns();
        return (width - (cols * CELL + (cols - 1) * GAP)) / 2;
    }

    private int maxScroll() {
        int rows = (entries.size() + columns() - 1) / columns();
        int content = rows * (CELL + LABEL + GAP);
        return Math.max(0, content - (height - TOP - GAP));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            parent.render(g, -1, -1, partialTick);
        } else {
            super.renderBackground(g, mouseX, mouseY, partialTick);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        g.fill(0, 0, width, height, 0xC0000000);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        if (enlarged >= 0) {
            renderLarge(g, entries.get(enlarged));
        } else {
            renderGrid(g, mouseX, mouseY);
        }
        g.pose().popPose();
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        long taken = entries.stream().filter(e -> e.file() != null).count();
        g.drawCenteredString(font, Component.translatable("gui.dungeontrain.earned_photos.count",
                taken + "/" + entries.size()), width / 2, 24, 0xFFAAAAAA);
        g.enableScissor(0, TOP - 2, width, height);
        int cols = columns();
        int left = gridLeft();
        for (int i = 0; i < entries.size(); i++) {
            int x = left + (i % cols) * (CELL + GAP);
            int y = TOP + (i / cols) * (CELL + LABEL + GAP) - scroll;
            if (y + CELL + LABEL < TOP || y > height) continue;
            EarnedPhotos.Entry entry = entries.get(i);
            boolean hover = entry.file() != null
                    && mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL && mouseY >= TOP;
            if (hover) g.fill(x - 2, y - 2, x + CELL + 2, y + CELL + 2, 0x60FFFFFF);
            if (entry.file() != null) {
                EarnedPhotoThumbnails.draw(g, entry.file(), x, y, CELL, GRID_SAMPLE);
            } else {
                drawMissing(g, x, y);
            }
            String label = font.plainSubstrByWidth(name(entries.get(i).id()).getString(), CELL + GAP);
            g.drawString(font, label, x + (CELL - font.width(label)) / 2, y + CELL + 2, 0xFFFFFFFF, true);
        }
        g.disableScissor();
    }

    /** A slot still waiting for its photo: an empty frame with a big question mark. */
    private void drawMissing(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + CELL, y + CELL, 0xFF6E6A62);
        g.fill(x + 3, y + 3, x + CELL - 3, y + CELL - 3, 0xFF2A2825);
        float scale = 4F;
        g.pose().pushPose();
        g.pose().translate(x + CELL / 2F - font.width("?") * scale / 2F, y + CELL / 2F - 4 * scale, 0);
        g.pose().scale(scale, scale, 1F);
        g.drawString(font, "?", 0, 0, 0xFF8C877D, false);
        g.pose().popPose();
    }

    private void renderLarge(GuiGraphics g, EarnedPhotos.Entry entry) {
        int side = Math.min(width, height) - 60;
        int x = (width - side) / 2;
        int y = (height - side - 14) / 2;
        EarnedPhotoThumbnails.draw(g, entry.file(), x, y, side, LARGE_SAMPLE);
        Component label = name(entry.id());
        g.drawString(font, label, (width - font.width(label)) / 2, y + side + 6, 0xFFFFFFFF, true);
    }

    /** The game's own name for what the photo was logged for — the biome, or the entity; ??? while missing. */
    private Component name(ResourceLocation id) {
        if (id == null) return Component.translatable("advancements.dungeontrain.hidden_description");
        String key = (biomes ? "biome." : "entity.") + id.getNamespace() + "." + id.getPath().replace('/', '.');
        return Component.translatable(key);
    }

    /** The entry under (mouseX, mouseY) in the grid, or -1. */
    private int entryAt(double mouseX, double mouseY) {
        if (mouseY < TOP) return -1;
        int cols = columns();
        int left = gridLeft();
        for (int i = 0; i < entries.size(); i++) {
            int x = left + (i % cols) * (CELL + GAP);
            int y = TOP + (i / cols) * (CELL + LABEL + GAP) - scroll;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (enlarged >= 0) {
            enlarged = -1;
            return true;
        }
        int hit = entryAt(mouseX, mouseY);
        if (hit >= 0) {
            if (entries.get(hit).file() != null) enlarged = hit;   // a missing slot has nothing to show
        } else {
            onClose();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (enlarged < 0) scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.round(scrollY * SCROLL_STEP)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && enlarged >= 0) {
            enlarged = -1;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
