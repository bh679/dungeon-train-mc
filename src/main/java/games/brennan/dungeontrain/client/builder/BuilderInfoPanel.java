package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderInfoLines;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What you're working on, above the tools that act on it.
 *
 * <p>The tools panel is all verbs — New, Save, Reset, Clear, Rename, Delete — and no nouns. The only
 * way to find out the build was an unnamed draft was to press Save and watch it ask for a name. This
 * says so first.</p>
 *
 * <p>Read-only by construction: it's an {@link AbstractWidget} rather than plain text so it takes
 * part in the screen's layout and z-order, but it never takes focus and never handles a click.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderInfoPanel extends AbstractWidget {

    private static final int BACKDROP = 0x90101010;
    private static final int BORDER = 0x40FFFFFF;
    private static final int NAME_COLOUR = 0xFFFFFF;
    private static final int DRAFT_COLOUR = 0xFFD070;
    private static final int DETAIL_COLOUR = 0xA0A0A0;

    static final int LINE_HEIGHT = 10;
    static final int PADDING = 4;

    /** Which half of the read-out a panel shows. Two panels are on screen at once, so they split. */
    enum Content {
        /** Name, type, stage — up to three lines. */
        IDENTITY(3),
        /** Size and weight — one line, and none at all when neither applies. */
        METRICS(1);

        private final int maxLines;

        Content(int maxLines) {
            this.maxLines = maxLines;
        }

        int maxLines() {
            return maxLines;
        }
    }

    private final Content content;

    BuilderInfoPanel(int x, int y, int width, int height, Content content) {
        super(x, y, width, height, Component.empty());
        this.content = content;
        this.active = false;
    }

    /** Height for {@code lines} rows of text. */
    static int heightFor(int lines) {
        return lines * LINE_HEIGHT + PADDING * 2;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        List<String> lines = currentLines(content);
        if (lines.isEmpty()) {
            return;   // a metrics panel with nothing to measure draws no empty box
        }
        int x = getX();
        int y = getY();
        g.fill(x, y, x + getWidth(), y + getHeight(), BACKDROP);
        g.renderOutline(x, y, getWidth(), getHeight(), BORDER);

        Minecraft mc = Minecraft.getInstance();
        int textY = y + PADDING;
        for (int i = 0; i < lines.size() && i < content.maxLines(); i++) {
            // The name gets the emphasis: it's the line you're looking for, and it's amber while the
            // build is a draft so "not saved anywhere" reads at a glance. A metrics panel has no
            // name line, so every row there is detail.
            int colour = content == Content.IDENTITY && i == 0
                    ? (BuilderBoundsState.isDraft() ? DRAFT_COLOUR : NAME_COLOUR)
                    : DETAIL_COLOUR;
            g.drawString(mc.font, lines.get(i), x + PADDING, textY, colour, false);
            textY += LINE_HEIGHT;
        }
    }

    /**
     * Assembled every frame — the underlying state changes on packets, not on screen rebuilds.
     *
     * <p>Shared with the in-game HUD and the main pause column so all three readouts of the same
     * build can't disagree; only whether the metrics row is included differs.</p>
     */
    static List<String> currentLines(Content content) {
        BuilderInfoLines.Data data = currentData();
        if (data == null) {
            return List.of();
        }
        UnaryOperator<String> translate = key -> Component.translatable(key).getString();
        return content == Content.METRICS
                ? BuilderInfoLines.metrics(data, translate)
                : BuilderInfoLines.identity(data, translate);
    }

    /** The current build, or null outside a builder world / before the server has said. */
    static BuilderInfoLines.Data currentData() {
        BuilderMode mode = BuilderMode.fromId(BuilderBoundsState.modeId()).orElse(null);
        if (mode == null) {
            return null;
        }
        return new BuilderInfoLines.Data(
                BuilderBoundsState.buildName(),
                BuilderDirtyState.hasUnsavedChanges(),
                mode,
                subTypeOf(BuilderBoundsState.subTypeId()),
                BuilderBoundsState.partKindId(),
                BuilderBoundsState.stageId(),
                dims(),
                BuilderBoundsState.weight(),
                BuilderBoundsState.trackKindId());
    }

    /** True while the name line should be drawn as a draft — shared with the HUD's colouring. */
    static boolean isDraft() {
        return BuilderBoundsState.isDraft();
    }

    /**
     * The build's size, from the volumes the client already has for the out-of-bounds wash — so this
     * costs nothing on the wire. Null when the mode parks no carriage.
     */
    private static int[] dims() {
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        if (volumes.isEmpty()) {
            return null;
        }
        BoundingBox box = volumes.get(0);
        return new int[] { box.getXSpan(), box.getYSpan(), box.getZSpan() };
    }

    private static BuilderNewOptions.SubType subTypeOf(String id) {
        for (BuilderNewOptions.SubType value : BuilderNewOptions.SubType.values()) {
            if (value.id().equals(id)) {
                return value;
            }
        }
        return BuilderNewOptions.SubType.WHOLE_CARRIAGE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // Nothing to announce beyond the lines themselves, which the narrator reads from the screen.
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return false;   // never eat a click meant for the button underneath
    }
}
