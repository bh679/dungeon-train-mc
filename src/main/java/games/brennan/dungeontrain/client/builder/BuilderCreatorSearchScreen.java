package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.net.BuilderCreatorResultsPacket;
import games.brennan.dungeontrain.net.BuilderCreatorSearchPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * <b>Find a builder</b> — the name search behind loading somebody else's builds, and a dev-build
 * screen throughout: nothing on a release build opens it, and a release server would answer its
 * searches with nothing anyway.
 *
 * <p>The search space is whoever has uploaded a build, which is the useful one here: a name that
 * matches no builder is a name with nothing to look at. Picking a row hands their uuid back to
 * {@link BuilderProfileScreen}, which lists that player's profile exactly as it lists the player's
 * own.</p>
 *
 * <p>Typing searches on a short delay rather than on every keystroke — each query is a round trip
 * through the server to the relay, and a name is typed faster than one completes. Answers carry the
 * query they belong to, so one that arrives after the player has typed past it is dropped rather
 * than replacing newer results.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderCreatorSearchScreen extends Screen {

    private static final int TITLE_TOP = 14;
    private static final int FIELD_TOP = 40;
    private static final int FIELD_WIDTH = 220;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;
    private static final int RESULTS_TOP = FIELD_TOP + 28;
    private static final int BUTTON_WIDTH = 200;
    private static final int BACK_BUTTON_BOTTOM_MARGIN = 28;
    private static final int STATUS_GAP = 14;
    private static final int NOTE_COLOUR = 0xA0A0A0;
    private static final int MAX_QUERY = 32;

    /** Ticks of quiet before a search is sent — long enough to finish a name, short enough to feel live. */
    private static final int SEARCH_DELAY_TICKS = 8;

    private final Screen lastScreen;
    private final Consumer<BuilderCreatorResultsPacket.Creator> onPick;

    private EditBox field;
    private String query = "";
    /** The query the results on screen answer, so a late reply to an older one can be recognised. */
    private String answered = "";
    private int ticksUntilSearch = -1;
    private boolean searching;
    private boolean unavailable;
    private List<BuilderCreatorResultsPacket.Creator> results = List.of();
    private int scrollRow;

    public BuilderCreatorSearchScreen(Screen lastScreen, Consumer<BuilderCreatorResultsPacket.Creator> onPick) {
        super(Component.translatable("gui.dungeontrain.builder.creators.title"));
        this.lastScreen = lastScreen;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        BuilderProfileState.listenForCreators(this::onResults);

        this.field = new EditBox(this.font, this.width / 2 - FIELD_WIDTH / 2, FIELD_TOP, FIELD_WIDTH, ROW_H,
                Component.translatable("gui.dungeontrain.builder.creators.search"));
        this.field.setHint(Component.translatable("gui.dungeontrain.builder.creators.hint"));
        this.field.setMaxLength(MAX_QUERY);
        this.field.setValue(query);   // survives the rebuild a pick-and-back triggers
        this.field.setResponder(text -> {
            this.query = text;
            this.ticksUntilSearch = text.trim().isEmpty() ? -1 : SEARCH_DELAY_TICKS;
            if (text.trim().isEmpty()) {
                this.results = List.of();
                this.answered = "";
                this.searching = false;
            }
        });
        addRenderableWidget(this.field);
        setInitialFocus(this.field);

        rebuild();
    }

    /** The result rows, and the Back button under them. Rebuilt whenever the list or the scroll moves. */
    private void rebuild() {
        clearWidgets();
        addRenderableWidget(this.field);

        int rows = visibleRows();
        this.scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, results.size() - rows)));
        for (int i = 0; i < rows && scrollRow + i < results.size(); i++) {
            BuilderCreatorResultsPacket.Creator creator = results.get(scrollRow + i);
            addRenderableWidget(Button.builder(rowLabel(creator), b -> pick(creator))
                    .bounds(this.width / 2 - BUTTON_WIDTH / 2, RESULTS_TOP + i * (ROW_H + ROW_GAP),
                            BUTTON_WIDTH, ROW_H)
                    .build());
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - BUTTON_WIDTH / 2,
                        this.height - BACK_BUTTON_BOTTOM_MARGIN, BUTTON_WIDTH, 20)
                .build());
    }

    /** How many rows fit between the field and the Back button, at least one. */
    private int visibleRows() {
        int available = this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP - RESULTS_TOP;
        return Math.max(1, available / (ROW_H + ROW_GAP));
    }

    private Component rowLabel(BuilderCreatorResultsPacket.Creator creator) {
        return Component.translatable("gui.dungeontrain.builder.creators.row",
                creator.name(), creator.builds());
    }

    /**
     * Hand the builder back and let the caller decide what to show. It does NOT return to
     * {@code lastScreen} first: the profile screen answers a pick by opening itself afresh against
     * that builder, and re-showing the old one in between would fire an ask for the old profile.
     */
    private void pick(BuilderCreatorResultsPacket.Creator creator) {
        onPick.accept(creator);
    }

    @Override
    public void tick() {
        super.tick();
        if (ticksUntilSearch < 0) return;
        if (ticksUntilSearch-- > 0) return;
        ticksUntilSearch = -1;
        String q = query.trim();
        if (q.isEmpty()) return;
        this.searching = true;
        // The search follows whatever pool the profile screen is showing — finding a builder on one
        // relay and then listing them on the other would name somebody with nothing to see.
        DungeonTrainNet.sendToServer(new BuilderCreatorSearchPacket(q, BuilderProfileState.live()));
    }

    /**
     * An answer arrived. Dropped unless it belongs to what is in the field now: searches are fired as
     * the player types and nothing promises they come back in order, so a slower answer to a shorter
     * query would otherwise overwrite the results for what was actually typed.
     */
    private void onResults(BuilderCreatorResultsPacket packet) {
        if (!packet.query().equals(query.trim())) return;
        this.searching = false;
        this.unavailable = !packet.found();
        this.answered = packet.query();
        this.results = packet.creators();
        this.scrollRow = 0;
        rebuild();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (results.size() > visibleRows()) {
            this.scrollRow -= (int) Math.signum(scrollY);
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_TOP, 0xFFFFFF);

        Component note = statusNote();
        if (note != null) {
            g.drawCenteredString(this.font, note, this.width / 2,
                    this.height - BACK_BUTTON_BOTTOM_MARGIN - STATUS_GAP + 2, NOTE_COLOUR);
        }
    }

    /**
     * The line under the list. "Nobody by that name" and "this build cannot search" are different
     * answers to the same empty list — one is worth retyping for, the other never will be.
     */
    private Component statusNote() {
        if (searching) return Component.translatable("gui.dungeontrain.builder.creators.searching");
        if (query.trim().isEmpty()) return Component.translatable("gui.dungeontrain.builder.creators.prompt");
        if (unavailable) return Component.translatable("gui.dungeontrain.builder.creators.unavailable");
        if (results.isEmpty() && !answered.isEmpty()) {
            return Component.translatable("gui.dungeontrain.builder.creators.none");
        }
        return null;
    }

    @Override
    public void removed() {
        super.removed();
        BuilderProfileState.listenForCreators(null);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(lastScreen);
    }
}
