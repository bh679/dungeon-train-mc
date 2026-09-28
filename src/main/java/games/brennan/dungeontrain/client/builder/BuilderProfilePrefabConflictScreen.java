package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.LootPrefabStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The build being loaded uses loot prefabs this install already has, with different contents —
 * which version of each should the chests roll?
 *
 * <p>Asked before anything is written (see {@code BuilderRelayDownload.Outcome#PREFAB_CONFLICT}),
 * so Cancel leaves the install exactly as it was. Each prefab is shown as two columns, <b>Yours</b>
 * and <b>Theirs</b>, and the player picks one by clicking it — the chosen column is tinted green.
 * Yours is the default, since changing a prefab changes every local template that shares it. A
 * third answer, <b>Rename theirs…</b>, files the build's version under a new name and points the
 * loaded template's chests at that instead, so neither version is lost.</p>
 *
 * <p>One Confirm for the lot: the answers replay the download with the chosen ids marked for
 * overwrite and the renames attached, and the prompt is not raised again on that replay.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfilePrefabConflictScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.builder.profile.prefab_conflict.";
    private static final int PANEL_MAX_WIDTH = 400;
    private static final int LINE_HEIGHT = 10;
    /** The prefab name is drawn at this scale so a block reads as a heading, not another line. */
    private static final float NAME_SCALE = 1.5f;
    private static final int HEADER_HEIGHT = 26;
    private static final int COLUMN_PAD = 4;
    private static final int COLUMN_GAP = 8;
    private static final int RENAME_HEIGHT = 16;
    private static final int BLOCK_GAP = 14;
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int TOP_TEXT_GAP = 6;

    private static final int BACKDROP = 0xB3000000;          // 70% black over the world
    private static final int COLOUR_HEADING = 0xFFFFFF;
    private static final int COLOUR_NAME = 0xFFD700;         // gold, like the roster's names
    private static final int COLOUR_NOTE = 0xA0A0A0;
    private static final int COLOUR_SAME = 0xC8C8C8;
    private static final int COLOUR_CHANGED = 0xFFD866;
    /** An entry only in the build's version: loading it would add this. */
    private static final int COLOUR_ADDED = 0x7FE07F;
    /** An entry only in this install's version: loading theirs would lose this. */
    private static final int COLOUR_LOST = 0xE07070;
    private static final int COLUMN_BG = 0x40000000;
    private static final int COLUMN_BG_HOVER = 0x50FFFFFF;
    private static final int CHOSEN_BG = 0x5540C040;         // greenish, the pick
    private static final int CHOSEN_BORDER = 0xFF60D060;
    private static final int DIVIDER = 0x60FFFFFF;

    /** What the player answered for one prefab. */
    private enum Choice { MINE, THEIRS, RENAME }

    private final Screen lastScreen;
    private final String buildName;
    private final List<TemplateLootPrefabs.Conflict> conflicts;
    /** Called with (ids to overwrite, old id → new id) on Confirm. */
    private final BiConsumer<Set<String>, Map<String, String>> onConfirm;

    private final Map<String, Choice> choices = new LinkedHashMap<>();
    private final Map<String, String> renames = new LinkedHashMap<>();
    private final List<LootPrefabDiff.Columns> columns = new ArrayList<>();
    private final List<Button> renameButtons = new ArrayList<>();
    private List<FormattedCharSequence> hintLines = List.of();
    private int listTop;
    private int listBottom;
    private int contentHeight;
    private int scroll;

    public BuilderProfilePrefabConflictScreen(Screen lastScreen, String buildName,
                                              List<TemplateLootPrefabs.Conflict> conflicts,
                                              BiConsumer<Set<String>, Map<String, String>> onConfirm) {
        super(Component.translatable(KEY + "title"));
        this.lastScreen = lastScreen;
        this.buildName = buildName == null ? "" : buildName;
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.onConfirm = onConfirm;
        for (TemplateLootPrefabs.Conflict c : this.conflicts) {
            columns.add(LootPrefabDiff.of(c.id(), c.localText(), c.incomingText()));
            choices.put(c.id(), Choice.MINE);
        }
    }

    /** The ids answered "use theirs" — what Confirm marks for overwrite. */
    Set<String> overwrite() {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Choice> e : choices.entrySet()) if (e.getValue() == Choice.THEIRS) out.add(e.getKey());
        return out;
    }

    /** The ids answered "rename theirs", old → new. */
    Map<String, String> renames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Choice> e : choices.entrySet()) {
            if (e.getValue() == Choice.RENAME && renames.containsKey(e.getKey())) out.put(e.getKey(), renames.get(e.getKey()));
        }
        return out;
    }

    // ---- layout ----

    private int panelWidth() {
        return Math.min(this.width - 40, PANEL_MAX_WIDTH);
    }

    private int panelLeft() {
        return this.width / 2 - panelWidth() / 2;
    }

    private int columnWidth() {
        return (panelWidth() - COLUMN_GAP) / 2;
    }

    private int columnRows(int i) {
        LootPrefabDiff.Columns c = columns.get(i);
        return 1 + Math.max(c.yours().size(), c.theirs().size()); // +1 for the Yours/Theirs heading
    }

    private int columnHeight(int i) {
        return columnRows(i) * LINE_HEIGHT + COLUMN_PAD * 2;
    }

    /** One conflict's block: the name header, the two columns, the rename row, and a gap. */
    private int blockHeight(int i) {
        return HEADER_HEIGHT + columnHeight(i) + RENAME_HEIGHT + BLOCK_GAP;
    }

    @Override
    protected void init() {
        renameButtons.clear();
        this.hintLines = this.font.split(
                Component.translatable(KEY + "hint", BuilderLabels.pretty(buildName)), panelWidth());
        this.listTop = 12 + LINE_HEIGHT + TOP_TEXT_GAP + hintLines.size() * LINE_HEIGHT + TOP_TEXT_GAP;
        this.listBottom = this.height - BUTTON_HEIGHT - 16;
        this.contentHeight = 0;
        for (int i = 0; i < conflicts.size(); i++) contentHeight += blockHeight(i);
        this.scroll = Math.min(scroll, maxScroll());

        for (TemplateLootPrefabs.Conflict c : conflicts) {
            String id = c.id();
            Button rename = Button.builder(renameLabel(id), b -> promptRename(id))
                    .bounds(0, 0, columnWidth(), RENAME_HEIGHT - 2).build();
            renameButtons.add(addRenderableWidget(rename));
        }
        placeWidgets();

        int buttonsY = this.height - BUTTON_HEIGHT - 8;
        int gap = 6;
        int left = this.width / 2 - BUTTON_WIDTH - gap / 2;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "confirm"), b -> confirm())
                .bounds(left, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(left + BUTTON_WIDTH + gap, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private Component renameLabel(String id) {
        String to = renames.get(id);
        return choices.get(id) == Choice.RENAME && to != null
                ? Component.translatable(KEY + "renamed_to", to)
                : Component.translatable(KEY + "rename_theirs");
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listBottom - listTop));
    }

    /** Put each rename button under its prefab's Theirs column, hidden when scrolled out. */
    private void placeWidgets() {
        int y = listTop - scroll;
        int right = panelLeft() + panelWidth();
        for (int i = 0; i < renameButtons.size(); i++) {
            Button b = renameButtons.get(i);
            int by = y + HEADER_HEIGHT + columnHeight(i) + 1;
            b.setX(right - columnWidth());
            b.setY(by);
            b.visible = by >= listTop && by + RENAME_HEIGHT <= listBottom;
            y += blockHeight(i);
        }
    }

    /**
     * Ask for the new name their version should be filed under. Suggests {@code <id>_2} on a free
     * number, refuses names already in the local library — a rename onto an existing prefab would
     * be the very overwrite this screen exists to avoid.
     */
    private void promptRename(String id) {
        Set<String> taken = new HashSet<>(LootPrefabStore.allIds());
        String suggestion = id;
        for (int n = 2; taken.contains(suggestion); n++) suggestion = id + "_" + n;
        this.minecraft.setScreen(new BuilderProfileNameScreen(this, this,
                Component.translatable(KEY + "rename_prompt", BuilderLabels.pretty(id)),
                suggestion, taken, false, chosen -> {
                    String name = chosen == null ? "" : chosen.trim().toLowerCase(Locale.ROOT);
                    if (name.isEmpty() || name.equals(id)) return;
                    renames.put(id, name);
                    choices.put(id, Choice.RENAME);
                    for (int i = 0; i < conflicts.size(); i++) {
                        if (conflicts.get(i).id().equals(id)) renameButtons.get(i).setMessage(renameLabel(id));
                    }
                }));
    }

    private void confirm() {
        this.minecraft.setScreen(lastScreen);
        if (onConfirm != null) onConfirm.accept(overwrite(), renames());
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
            placeWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || mouseY < listTop || mouseY >= listBottom) return false;
        int hit = columnAt(mouseX, mouseY);
        if (hit == 0) return false;
        String id = conflicts.get(Math.abs(hit) - 1).id();
        choices.put(id, hit < 0 ? Choice.MINE : Choice.THEIRS);
        for (int i = 0; i < conflicts.size(); i++) {
            if (conflicts.get(i).id().equals(id)) renameButtons.get(i).setMessage(renameLabel(id));
        }
        return true;
    }

    /**
     * Which column is under the mouse: {@code -(i+1)} for conflict i's Yours, {@code +(i+1)} for
     * its Theirs, 0 for neither.
     */
    private int columnAt(double mouseX, double mouseY) {
        int y = listTop - scroll;
        int left = panelLeft();
        int colWidth = columnWidth();
        for (int i = 0; i < conflicts.size(); i++) {
            int top = y + HEADER_HEIGHT;
            int bottom = top + columnHeight(i);
            if (mouseY >= top && mouseY < bottom) {
                if (mouseX >= left && mouseX < left + colWidth) return -(i + 1);
                int rightCol = left + colWidth + COLUMN_GAP;
                if (mouseX >= rightCol && mouseX < rightCol + colWidth) return i + 1;
                return 0;
            }
            y += blockHeight(i);
        }
        return 0;
    }

    // ---- render ----

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, BACKDROP);
    }

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
        int hover = mouseY >= listTop && mouseY < listBottom ? columnAt(mouseX, mouseY) : 0;
        int blockY = listTop - scroll;
        for (int i = 0; i < conflicts.size(); i++) {
            if (blockY + blockHeight(i) >= listTop && blockY < listBottom) renderBlock(g, i, blockY, hover);
            blockY += blockHeight(i);
        }
        g.disableScissor();
        // The rename buttons sit inside the list but are drawn by super.render above the scissor;
        // hidden ones are already invisible via placeWidgets, so nothing bleeds past the list.
    }

    private void renderBlock(GuiGraphics g, int i, int top, int hover) {
        TemplateLootPrefabs.Conflict conflict = conflicts.get(i);
        LootPrefabDiff.Columns c = columns.get(i);
        int left = panelLeft();
        int right = left + panelWidth();
        int colWidth = columnWidth();
        int rightCol = left + colWidth + COLUMN_GAP;

        // Heading: the prefab's name, large and gold, with a divider under it.
        g.pose().pushPose();
        g.pose().translate(left, top + 4, 0);
        g.pose().scale(NAME_SCALE, NAME_SCALE, 1f);
        g.drawString(this.font, BuilderLabels.pretty(conflict.id()), 0, 0, COLOUR_NAME);
        g.pose().popPose();
        g.fill(left, top + HEADER_HEIGHT - 4, right, top + HEADER_HEIGHT - 3, DIVIDER);

        int colTop = top + HEADER_HEIGHT;
        int colHeight = columnHeight(i);
        Choice choice = choices.get(conflict.id());
        renderColumn(g, c.yours(), left, colTop, colWidth, colHeight, true,
                choice == Choice.MINE, hover == -(i + 1));
        renderColumn(g, c.theirs(), rightCol, colTop, colWidth, colHeight, false,
                choice == Choice.THEIRS || choice == Choice.RENAME, hover == i + 1);
    }

    private void renderColumn(GuiGraphics g, List<LootPrefabDiff.Line> lines, int x, int y, int width,
                              int height, boolean yours, boolean chosen, boolean hovered) {
        g.fill(x, y, x + width, y + height, chosen ? CHOSEN_BG : hovered ? COLUMN_BG_HOVER : COLUMN_BG);
        if (chosen) {
            g.fill(x, y, x + width, y + 1, CHOSEN_BORDER);
            g.fill(x, y + height - 1, x + width, y + height, CHOSEN_BORDER);
            g.fill(x, y, x + 1, y + height, CHOSEN_BORDER);
            g.fill(x + width - 1, y, x + width, y + height, CHOSEN_BORDER);
        }
        int tx = x + COLUMN_PAD;
        int ty = y + COLUMN_PAD;
        g.drawString(this.font, Component.translatable(KEY + (yours ? "yours" : "theirs")), tx, ty, COLOUR_HEADING);
        ty += LINE_HEIGHT;
        int textWidth = width - COLUMN_PAD * 2;
        for (LootPrefabDiff.Line line : lines) {
            FormattedCharSequence clipped = net.minecraft.locale.Language.getInstance()
                    .getVisualOrder(this.font.substrByWidth(textOf(line), textWidth));
            g.drawString(this.font, clipped, tx, ty, colourOf(line, yours));
            ty += LINE_HEIGHT;
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
