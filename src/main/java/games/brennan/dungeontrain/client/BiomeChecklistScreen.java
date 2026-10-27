package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.TerraOmnia;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Terra Omnia's checklist: every biome the game has, by its own name, ridden ones ticked and the rest
 * greyed out. Grouped as {@link TerraOmnia#ordered} lists them (the vanilla Overworld, Nether, End, then each
 * mod), ridden first within a group. Opened by clicking the advancement ({@link AdvancementTrackClick}),
 * earned or not, drawn over the advancements screen the way a photo album is ({@link EarnedPhotoAlbumScreen}).
 * Scroll with the wheel, the scrollbar (drag its thumb or click its track) or the arrow, page, Home and End
 * keys; a click anywhere else, or Esc, puts it away.
 */
public final class BiomeChecklistScreen extends Screen {

    private static final float OVERLAY_Z = 1000F;
    private static final int TOP = 44;
    private static final int ROW = 11;
    private static final int GROUP_GAP = 6;
    private static final int COLUMN_WIDTH = 150;
    private static final int MAX_COLUMNS = 3;
    private static final int MARGIN = 10;
    private static final int SCROLL_STEP = 24;
    private static final int RIDDEN_COLOUR = 0xFFFFFFFF;
    private static final int MISSING_COLOUR = 0xFF6E6E6E;
    private static final int TICK_COLOUR = 0xFF55FF55;
    private static final int BAR_WIDTH = 6;
    private static final int BAR_MIN_THUMB = 16;
    private static final int TRACK_COLOUR = 0xFF2A2A2A;
    private static final int THUMB_COLOUR = 0xFF8A8A8A;
    private static final int THUMB_HOVER_COLOUR = 0xFFC8C8C8;
    private static final int FADE_HEIGHT = 16;

    /** One line of the checklist: a biome, whether it has been ridden, and whether a new group starts with it. */
    private record Line(ResourceLocation biome, boolean ridden, boolean groupStart) {}

    private final Screen parent;
    private List<Line> lines = List.of();
    private long riddenCount;
    private int scroll;
    private int maxScroll;
    private int[] column = new int[0];
    private int[] offset = new int[0];
    /** Where on the thumb the drag took hold, while the thumb is being dragged; -1 otherwise. */
    private double dragGrip = -1;

    public BiomeChecklistScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (parent != null && (parent.width != width || parent.height != height)) {
            parent.resize(minecraft, width, height);
        }
        lines = buildLines();
        riddenCount = lines.stream().filter(Line::ridden).count();
        layout();
        setScroll(scroll);
    }

    /** Every biome in this world's registry, in checklist order: by group, ridden first within each. */
    private List<Line> buildLines() {
        if (minecraft == null || minecraft.level == null) return List.of();
        Map<Integer, List<ResourceLocation>> groups = new LinkedHashMap<>();
        TerraOmnia.ordered(minecraft.level.registryAccess().registryOrThrow(Registries.BIOME))
            .forEach((id, group) -> groups.computeIfAbsent(group, k -> new ArrayList<>()).add(id));
        Set<ResourceLocation> ridden = RiddenBiomes.get();
        List<Line> out = new ArrayList<>();
        for (List<ResourceLocation> group : groups.values()) {
            List<ResourceLocation> sorted = new ArrayList<>(group);
            sorted.sort(Comparator.comparing((ResourceLocation id) -> !ridden.contains(id)));   // stable: ids stay in order
            for (int i = 0; i < sorted.size(); i++) {
                out.add(new Line(sorted.get(i), ridden.contains(sorted.get(i)), i == 0 && !out.isEmpty()));
            }
        }
        return List.copyOf(out);
    }

    private int columns() {
        return Math.max(1, Math.min(MAX_COLUMNS, (width - 2 * MARGIN - BAR_WIDTH) / COLUMN_WIDTH));
    }

    /**
     * Each line's column and y offset within it. Columns read top to bottom and hold about the same number
     * of lines; a gap opens before each new group's first line.
     */
    private void layout() {
        int per = Math.max(1, (lines.size() + columns() - 1) / columns());
        column = new int[lines.size()];
        offset = new int[lines.size()];
        int tallest = 0;
        int y = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (i % per == 0) y = 0;
            else if (lines.get(i).groupStart()) y += GROUP_GAP;
            column[i] = i / per;
            offset[i] = y;
            y += ROW;
            tallest = Math.max(tallest, y);
        }
        maxScroll = Math.max(0, tallest - (height - TOP - MARGIN));
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
        g.fill(0, 0, width, height, 0xE8000000);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        g.pose().translate(0, 0, OVERLAY_Z);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        g.drawCenteredString(font, riddenCount + "/" + lines.size(), width / 2, 24, 0xFFAAAAAA);
        g.enableScissor(0, TOP - 2, width, height);
        int cols = columns();
        int left = (width - cols * COLUMN_WIDTH) / 2;
        for (int i = 0; i < lines.size(); i++) {
            int x = left + column[i] * COLUMN_WIDTH;
            int y = TOP + offset[i] - scroll;
            if (y + ROW < TOP || y > height) continue;
            drawLine(g, lines.get(i), x, y);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            if (scroll < maxScroll) g.fillGradient(0, height - FADE_HEIGHT, width - BAR_WIDTH - MARGIN, height, 0x00000000, 0xF0000000);
            drawScrollbar(g, mouseX, mouseY);
        }
        g.pose().popPose();
    }

    private int barLeft() {
        return width - MARGIN - BAR_WIDTH;
    }

    private int trackHeight() {
        return height - TOP - MARGIN;
    }

    /** Thumb height: the share of the list in view, never too small to grab. */
    private int thumbHeight() {
        int track = trackHeight();
        return Math.max(BAR_MIN_THUMB, Math.min(track, track * track / (track + maxScroll)));
    }

    private int thumbTop() {
        return TOP + (int) Math.round((double) scroll / maxScroll * (trackHeight() - thumbHeight()));
    }

    private boolean onBar(double mouseX, double mouseY) {
        return maxScroll > 0 && mouseX >= barLeft() - 2 && mouseX < barLeft() + BAR_WIDTH + 2
                && mouseY >= TOP && mouseY < TOP + trackHeight();
    }

    private boolean onThumb(double mouseX, double mouseY) {
        return onBar(mouseX, mouseY) && mouseY >= thumbTop() && mouseY < thumbTop() + thumbHeight();
    }

    private void drawScrollbar(GuiGraphics g, int mouseX, int mouseY) {
        int x = barLeft();
        g.fill(x, TOP, x + BAR_WIDTH, TOP + trackHeight(), TRACK_COLOUR);
        int top = thumbTop();
        boolean lit = dragGrip >= 0 || onThumb(mouseX, mouseY);
        g.fill(x, top, x + BAR_WIDTH, top + thumbHeight(), lit ? THUMB_HOVER_COLOUR : THUMB_COLOUR);
    }

    /** Scroll so the thumb's top sits at {@code thumbY}. */
    private void scrollThumbTo(double thumbY) {
        int room = trackHeight() - thumbHeight();
        setScroll(room <= 0 ? 0 : (int) Math.round((thumbY - TOP) / room * maxScroll));
    }

    private void setScroll(int value) {
        scroll = Math.max(0, Math.min(maxScroll, value));
    }

    private void drawLine(GuiGraphics g, Line line, int x, int y) {
        if (line.ridden()) g.drawString(font, "✔", x, y, TICK_COLOUR, true);
        String name = font.plainSubstrByWidth(nameOf(line.biome()).getString(), COLUMN_WIDTH - 14);
        g.drawString(font, name, x + 10, y, line.ridden() ? RIDDEN_COLOUR : MISSING_COLOUR, line.ridden());
    }

    /** The game's own name for a biome; its id, tidied, when no language names it ({@code waterfall_ponds} → Waterfall Ponds). */
    private static Component nameOf(ResourceLocation biome) {
        String key = "biome." + biome.getNamespace() + "." + biome.getPath().replace('/', '.');
        if (Language.getInstance().has(key)) return Component.translatable(key);
        String path = biome.getPath().substring(biome.getPath().lastIndexOf('/') + 1);
        StringBuilder name = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.literal(name.toString());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (onBar(mouseX, mouseY)) {
            // Grab the thumb where it was clicked; a click on the track jumps the thumb's middle there first.
            if (!onThumb(mouseX, mouseY)) scrollThumbTo(mouseY - thumbHeight() / 2.0);
            dragGrip = mouseY - thumbTop();
            return true;
        }
        onClose();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragGrip < 0) return false;
        scrollThumbTo(mouseY - dragGrip);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragGrip = -1;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        setScroll(scroll - (int) Math.round(scrollY * SCROLL_STEP));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int page = Math.max(ROW, trackHeight() - 2 * ROW);
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> setScroll(scroll - ROW);
            case GLFW.GLFW_KEY_DOWN -> setScroll(scroll + ROW);
            case GLFW.GLFW_KEY_PAGE_UP -> setScroll(scroll - page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScroll(scroll + page);
            case GLFW.GLFW_KEY_HOME -> setScroll(0);
            case GLFW.GLFW_KEY_END -> setScroll(maxScroll);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Pauses when the screen it opened over does (the advancements screen), so the train doesn't run on behind it. */
    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }
}
