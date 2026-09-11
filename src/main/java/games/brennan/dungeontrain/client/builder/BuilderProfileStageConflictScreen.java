package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.TemplateStages;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The build being loaded came with Stages that already exist here under the same ids, with
 * different settings.
 *
 * <p>Asked before anything is written — the fetch that raised it was a read — and answered per
 * Stage: <b>Keep mine</b> leaves the local Stage exactly as it is (the build still links it by id,
 * so it gates the way this install says), <b>Use theirs</b> writes the relay's definition over it.
 * Every row starts on Keep mine, so nothing local changes without a deliberate toggle. Apply
 * replays the download with the chosen ids; Cancel returns to the list with nothing written.</p>
 *
 * <p>Each row shows the two definitions side by side — name, Diff-Level band, phases — with the
 * fields that differ tinted, because the id alone says nothing about what the choice changes.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileStageConflictScreen extends Screen {

    static final String KEY_TITLE = "gui.dungeontrain.builder.profile.stage_conflict.title";
    static final String KEY_INTRO = "gui.dungeontrain.builder.profile.stage_conflict.intro";
    static final String KEY_YOURS = "gui.dungeontrain.builder.profile.stage_conflict.yours";
    static final String KEY_THEIRS = "gui.dungeontrain.builder.profile.stage_conflict.theirs";
    static final String KEY_KEEP_MINE = "gui.dungeontrain.builder.profile.stage_conflict.keep_mine";
    static final String KEY_USE_THEIRS = "gui.dungeontrain.builder.profile.stage_conflict.use_theirs";
    static final String KEY_APPLY = "gui.dungeontrain.builder.profile.stage_conflict.apply";
    static final String KEY_LEVELS = "gui.dungeontrain.builder.profile.stage_conflict.levels";
    static final String KEY_ALL_LEVELS = "gui.dungeontrain.builder.profile.stage_conflict.all_levels";
    static final String KEY_ALL_PHASES = "gui.dungeontrain.builder.profile.stage_conflict.all_phases";
    static final String KEY_UNREADABLE = "gui.dungeontrain.builder.profile.stage_conflict.unreadable";

    private static final int LINE = 11;
    private static final int ROW_HEIGHT = LINE * 4 + 10;
    private static final int PANEL_WIDTH = 300;
    private static final int TOGGLE_WIDTH = 80;
    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int COLOUR_TEXT = 0xFFFFFF;
    private static final int COLOUR_NOTE = 0xA0A0A0;
    private static final int COLOUR_CHANGED = 0xFFE060;
    private static final int COLOUR_ID = 0x80D0FF;

    private final Screen lastScreen;
    private final String buildName;
    private final List<TemplateStages.Conflict> conflicts;
    /** The ids to take over the local copy — replaced, never mutated, on every toggle. */
    private Set<String> useTheirs = Set.of();
    private final Consumer<List<String>> onApply;

    private final List<Button> toggles = new ArrayList<>();
    private int listTop;
    private int listBottom;
    private int scroll;

    public BuilderProfileStageConflictScreen(Screen lastScreen, String buildName,
                                             List<TemplateStages.Conflict> conflicts,
                                             Consumer<List<String>> onApply) {
        super(Component.translatable(KEY_TITLE));
        this.lastScreen = lastScreen;
        this.buildName = buildName == null ? "" : buildName;
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.onApply = onApply;
    }

    // ---- layout ----

    @Override
    protected void init() {
        this.toggles.clear();
        this.listTop = 44;
        this.listBottom = this.height - 34;
        int left = this.width / 2 - PANEL_WIDTH / 2;
        for (TemplateStages.Conflict c : conflicts) {
            Button b = Button.builder(toggleLabel(c.id()), btn -> toggle(c.id(), btn))
                    .bounds(left + PANEL_WIDTH - TOGGLE_WIDTH, 0, TOGGLE_WIDTH, BUTTON_HEIGHT).build();
            toggles.add(addRenderableWidget(b));
        }
        int buttonsY = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable(KEY_APPLY), b -> apply())
                .bounds(this.width / 2 - BUTTON_WIDTH - 2, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(this.width / 2 + 2, buttonsY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        placeToggles();
    }

    private void placeToggles() {
        for (int i = 0; i < toggles.size(); i++) {
            int y = rowTop(i) + 1;
            Button b = toggles.get(i);
            b.setY(y);
            b.visible = y + BUTTON_HEIGHT > listTop && y < listBottom;
        }
    }

    private int rowTop(int index) {
        return listTop + index * ROW_HEIGHT - scroll;
    }

    private int maxScroll() {
        return Math.max(0, conflicts.size() * ROW_HEIGHT - (listBottom - listTop));
    }

    // ---- choices ----

    private void toggle(String id, Button button) {
        Set<String> next = new HashSet<>(useTheirs);
        if (!next.remove(id)) next.add(id);
        this.useTheirs = Set.copyOf(next);
        button.setMessage(toggleLabel(id));
    }

    private Component toggleLabel(String id) {
        return Component.translatable(useTheirs.contains(id) ? KEY_USE_THEIRS : KEY_KEEP_MINE);
    }

    private void apply() {
        List<String> chosen = conflicts.stream().map(TemplateStages.Conflict::id)
                .filter(useTheirs::contains).collect(Collectors.toList());
        this.minecraft.setScreen(lastScreen);
        if (onApply != null) onApply.accept(chosen);
    }

    /** Test seam: the ids currently set to "use theirs". */
    Set<String> chosenIds() {
        return useTheirs;
    }

    // ---- input ----

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = maxScroll();
        if (max == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        this.scroll = Math.max(0, Math.min(max, this.scroll - (int) (scrollY * ROW_HEIGHT / 2)));
        placeToggles();
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }

    // ---- rendering ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, getTitle(), this.width / 2, 10, COLOUR_TEXT);
        g.drawCenteredString(this.font,
                Component.translatable(KEY_INTRO, Component.literal(BuilderLabels.pretty(buildName))),
                this.width / 2, 24, COLOUR_NOTE);

        int left = this.width / 2 - PANEL_WIDTH / 2;
        g.enableScissor(0, listTop, this.width, listBottom);
        for (int i = 0; i < conflicts.size(); i++) {
            int top = rowTop(i);
            if (top + ROW_HEIGHT < listTop || top > listBottom) continue;
            drawRow(g, conflicts.get(i), left, top);
        }
        g.disableScissor();
    }

    private void drawRow(GuiGraphics g, TemplateStages.Conflict c, int left, int top) {
        g.drawString(this.font, c.id(), left, top + 6, COLOUR_ID);
        Summary mine = summarise(c.id(), c.localJson());
        Summary theirs = summarise(c.id(), c.incomingJson());
        Set<String> changed = changedFields(mine, theirs);
        int colYours = left;
        int colTheirs = left + (PANEL_WIDTH - TOGGLE_WIDTH) / 2;
        int y = top + 6 + LINE;
        g.drawString(this.font, Component.translatable(KEY_YOURS), colYours, y, COLOUR_NOTE);
        g.drawString(this.font, Component.translatable(KEY_THEIRS), colTheirs, y, COLOUR_NOTE);
        y += LINE;
        drawPair(g, mine.nameLine(), theirs.nameLine(), changed.contains(FIELD_NAME), colYours, colTheirs, y);
        y += LINE;
        drawPair(g, mine.levelsLine(), theirs.levelsLine(), changed.contains(FIELD_LEVELS), colYours, colTheirs, y);
        y += LINE;
        drawPair(g, mine.phasesLine(), theirs.phasesLine(), changed.contains(FIELD_PHASES), colYours, colTheirs, y);
        g.fill(left, top + ROW_HEIGHT - 2, left + PANEL_WIDTH, top + ROW_HEIGHT - 1, 0x40FFFFFF);
    }

    private void drawPair(GuiGraphics g, Component a, Component b, boolean changed, int xa, int xb, int y) {
        int colour = changed ? COLOUR_CHANGED : COLOUR_TEXT;
        g.drawString(this.font, a, xa, y, colour);
        g.drawString(this.font, b, xb, y, colour);
    }

    static final String FIELD_NAME = "name";
    static final String FIELD_LEVELS = "levels";
    static final String FIELD_PHASES = "phases";

    /**
     * One side of a row, as the values the tint compares on — never the rendered text, which
     * depends on the loaded language. {@code readable} false is the unreadable case: every line
     * says "could not show" and every field counts as changed against a readable side.
     */
    record Summary(boolean readable, String name, int minLevel, int maxLevel, Set<TrainPhase> phases) {

        Component nameLine() {
            return readable ? Component.literal(name) : Component.translatable(KEY_UNREADABLE);
        }

        Component levelsLine() {
            if (!readable) return Component.translatable(KEY_UNREADABLE);
            return maxLevel == TemplateGate.ALL
                    ? Component.translatable(KEY_LEVELS, minLevel, Component.translatable(KEY_ALL_LEVELS))
                    : Component.translatable(KEY_LEVELS, minLevel, maxLevel);
        }

        Component phasesLine() {
            if (!readable) return Component.translatable(KEY_UNREADABLE);
            if (phases.size() == TrainPhase.values().length) return Component.translatable(KEY_ALL_PHASES);
            return Component.literal(phases.stream().sorted().map(p -> p.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", ")));
        }
    }

    /**
     * One side, parsed. Pure — package-private so the diff classification can be tested without a
     * screen. Unreadable text (the wire clipped it, or a relay sent something else) gives an
     * unreadable summary rather than a crash mid-render.
     */
    static Summary summarise(String id, String json) {
        Stage s = StageStore.parseJsonText(id, json);
        if (s == null) return new Summary(false, "", 0, 0, Set.of());
        TemplateGate gate = s.gate();
        return new Summary(true, s.name(), gate.minLevel(), gate.maxLevel(), Set.copyOf(gate.phases()));
    }

    /** Which of the three lines differ between two sides — the tint decision. */
    static Set<String> changedFields(Summary a, Summary b) {
        Set<String> out = new HashSet<>();
        if (!a.readable || !b.readable) {
            if (a.readable != b.readable) out.addAll(Set.of(FIELD_NAME, FIELD_LEVELS, FIELD_PHASES));
            return Set.copyOf(out);
        }
        if (!a.name.equals(b.name)) out.add(FIELD_NAME);
        if (a.minLevel != b.minLevel || a.maxLevel != b.maxLevel) out.add(FIELD_LEVELS);
        if (!a.phases.equals(b.phases)) out.add(FIELD_PHASES);
        return Set.copyOf(out);
    }

    /** As above from the wire text of both sides — the test seam. */
    static Set<String> changedFields(String id, String localJson, String incomingJson) {
        return changedFields(summarise(id, localJson), summarise(id, incomingJson));
    }
}
