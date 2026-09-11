package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.TemplateLootPrefabs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The build being loaded uses loot prefabs this install already has, with different contents —
 * which version of each should the chests roll?
 *
 * <p>Asked before anything is written (see {@code BuilderRelayDownload.Outcome#PREFAB_CONFLICT}),
 * so Cancel leaves the install exactly as it was. Each prefab gets its own answer: <b>Keep mine</b>
 * — the default, since changing a prefab changes every local template that shares it — or
 * <b>Use theirs</b>, which writes the build's version over this install's. Both versions are laid
 * out side by side so the choice is made on what is actually in them, not on a name.</p>
 *
 * <p>The answer replays the download with the chosen ids, and only those, marked for overwrite; the
 * prompt is not raised again on that replay.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfilePrefabConflictScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.builder.profile.prefab_conflict.";
    private static final int PANEL_MAX_WIDTH = 380;
    private static final int LINE_HEIGHT = 10;
    private static final int HEADER_HEIGHT = 24;
    private static final int BLOCK_GAP = 10;
    private static final int TOGGLE_WIDTH = 84;
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int TOP_TEXT_GAP = 6;

    private static final int COLOUR_HEADING = 0xFFFFFF;
    private static final int COLOUR_NOTE = 0xA0A0A0;
    private static final int COLOUR_SAME = 0xA0A0A0;
    private static final int COLOUR_CHANGED = 0xFFD866;
    /** An entry only in the build's version: loading it would add this. */
    private static final int COLOUR_ADDED = 0x7FE07F;
    /** An entry only in this install's version: loading theirs would lose this. */
    private static final int COLOUR_LOST = 0xE07070;

    private final Screen lastScreen;
    private final String buildName;
    private final List<TemplateLootPrefabs.Conflict> conflicts;
    private final Consumer<Set<String>> onApply;
    /** The ids the player has flipped to "use theirs". Everything else keeps this install's copy. */
    private final Set<String> useTheirs = new HashSet<>();

    private final List<LootPrefabDiff.Columns> columns = new ArrayList<>();
    private final List<Button> toggles = new ArrayList<>();
    private List<FormattedCharSequence> hintLines = List.of();
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private int scroll;

    public BuilderProfilePrefabConflictScreen(Screen lastScreen, String buildName,
                                              List<TemplateLootPrefabs.Conflict> conflicts,
                                              Consumer<Set<String>> onApply) {
        super(Component.translatable(KEY + "title"));
        this.lastScreen = lastScreen;
        this.buildName = buildName == null ? "" : buildName;
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.onApply = onApply;
        for (TemplateLootPrefabs.Conflict c : this.conflicts) {
            columns.add(LootPrefabDiff.of(c.id(), c.localText(), c.incomingText()));
        }
    }

    /** The ids currently answered "use theirs" — what Apply hands on. */
    Set<String> chosen() {
        return Set.copyOf(useTheirs);
    }

    // ---- layout ----

    private int panelWidth() {
        return Math.min(this.width - 40, PANEL_MAX_WIDTH);
    }

    private int panelLeft() {
        return this.width / 2 - panelWidth() / 2;
    }

    private int columnWidth() {
        return (panelWidth() - 12) / 2;
    }

    /** The height of one conflict's block: its header, then the taller of its two columns. */
    private int blockHeight(int i) {
        LootPrefabDiff.Columns c = columns.get(i);
        int rows = 1 + Math.max(c.yours().size(), c.theirs().size()); // +1 for the Yours/Theirs heading
        return HEADER_HEIGHT + rows * LINE_HEIGHT + BLOCK_GAP;
    }

    @Override
    protected void init() {
        toggles.clear();
        this.hintLines = this.font.split(
                Component.translatable(KEY + "hint", BuilderLabels.pretty(buildName)), panelWidth());
        int top = 12 + LINE_HEIGHT + TOP_TEXT_GAP + hintLines.size() * LINE_HEIGHT + TOP_TEXT_GAP;
        this.listTop = top;
        this.listBottom = this.height - BUTTON_HEIGHT - 16;
        this.contentHeight = 0;
        for (int i = 0; i < conflicts.size(); i++) contentHeight += blockHeight(i);
        this.scroll = Math.min(scroll, maxScroll());

        for (TemplateLootPrefabs.Conflict c : conflicts) {
            String id = c.id();
            Button toggle = Button.builder(toggleLabel(id), b -> {
                if (!useTheirs.remove(id)) useTheirs.add(id);
                b.setMessage(toggleLabel(id));
            }).bounds(0, 0, TOGGLE_WIDTH, BUTTON_HEIGHT).build();
            toggles.add(addRenderableWidget(toggle));
        }
        placeToggles();

        int buttonsY = this.height - BUTTON_HEIGHT - 8;
        int gap = 6;
        int left = this.width / 2 - BUTTON_WIDTH - gap / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "apply"), b -> apply())
                .bounds(left, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left + BUTTON_WIDTH + gap, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private Component toggleLabel(String id) {
        return Component.translatable(KEY + (useTheirs.contains(id) ? "use_theirs" : "keep_mine"));
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listBottom - listTop));
    }

    /** Put each toggle beside its prefab's heading, and hide the ones scrolled out of the list. */
    private void placeToggles() {
        int y = listTop - scroll;
        int right = panelLeft() + panelWidth();
        for (int i = 0; i < toggles.size(); i++) {
            Button b = toggles.get(i);
            b.setX(right - TOGGLE_WIDTH);
            b.setY(y);
            b.visible = y >= listTop && y + BUTTON_HEIGHT <= listBottom;
            y += blockHeight(i);
        }
    }

    private void apply() {
        this.minecraft.setScreen(lastScreen);
        if (onApply != null) onApply.accept(chosen());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }

    // ---- input ----

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop && mouseY < listBottom && maxScroll() > 0) {
            scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - scrollY * LINE_HEIGHT * 2));
            placeToggles();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---- render ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, getTitle(), this.width / 2, 12, COLOUR_HEADING);
        int y = 12 + LINE_HEIGHT + TOP_TEXT_GAP;
        for (FormattedCharSequence line : hintLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, COLOUR_NOTE);
            y += LINE_HEIGHT;
        }
        g.enableScissor(0, listTop, this.width, listBottom);
        int blockY = listTop - scroll;
        for (int i = 0; i < conflicts.size(); i++) {
            if (blockY + blockHeight(i) >= listTop && blockY < listBottom) renderBlock(g, i, blockY);
            blockY += blockHeight(i);
        }
        g.disableScissor();
    }

    private void renderBlock(GuiGraphics g, int i, int top) {
        LootPrefabDiff.Columns c = columns.get(i);
        int left = panelLeft();
        int colWidth = columnWidth();
        int rightCol = left + colWidth + 12;
        g.drawString(this.font, BuilderLabels.pretty(conflicts.get(i).id()), left, top + 6, COLOUR_HEADING);
        int y = top + HEADER_HEIGHT;
        g.drawString(this.font, Component.translatable(KEY + "yours"), left, y, COLOUR_HEADING);
        g.drawString(this.font, Component.translatable(KEY + "theirs"), rightCol, y, COLOUR_HEADING);
        y += LINE_HEIGHT;
        renderColumn(g, c.yours(), left, y, colWidth, true);
        renderColumn(g, c.theirs(), rightCol, y, colWidth, false);
    }

    private void renderColumn(GuiGraphics g, List<LootPrefabDiff.Line> lines, int x, int y, int width,
                              boolean yours) {
        for (LootPrefabDiff.Line line : lines) {
            Component text = textOf(line);
            FormattedCharSequence clipped = net.minecraft.locale.Language.getInstance()
                    .getVisualOrder(this.font.substrByWidth(text, width));
            g.drawString(this.font, clipped, x, y, colourOf(line, yours));
            y += LINE_HEIGHT;
        }
    }

    /**
     * The colour a line's state earns, read from this install's point of view: an entry only in
     * the build's version is something loading it would <i>add</i>, one only in this install's
     * version is something loading theirs would <i>lose</i>.
     */
    static int colourOf(LootPrefabDiff.Line line, boolean yours) {
        return switch (line.state()) {
            case SAME -> COLOUR_SAME;
            case CHANGED -> COLOUR_CHANGED;
            case ONLY_HERE -> yours ? COLOUR_LOST : COLOUR_ADDED;
            case UNREADABLE -> COLOUR_NOTE;
        };
    }

    /** The lang key each line renders through, with its arguments. */
    static Component textOf(LootPrefabDiff.Line line) {
        return switch (line.kind()) {
            case UNREADABLE -> Component.translatable(KEY + "unreadable");
            case BLOCK -> Component.translatable(KEY + "block", blockName(line.item()));
            case FILL -> line.weight() == ContainerContentsPool.FILL_ALL
                    ? Component.translatable(KEY + "fill_all", line.count())
                    : Component.translatable(KEY + "fill", line.count(), line.weight());
            case ENTRY -> Component.translatable(KEY + "entry", line.count(), itemName(line.item()), line.weight());
        };
    }

    private static Component itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item == null ? Component.literal(id) : item.getDescription();
    }

    private static Component blockName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Block block = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        return block == null ? Component.literal(id) : block.getName();
    }
}
