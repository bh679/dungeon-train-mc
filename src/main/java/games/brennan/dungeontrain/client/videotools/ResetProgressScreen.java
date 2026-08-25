package games.brennan.dungeontrain.client.videotools;

import games.brennan.dungeontrain.client.reset.ProfileItem;
import games.brennan.dungeontrain.client.reset.ProfileWipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The confirm step for the Video Tools reset: everything {@link ProfileWipe#survey()} found, named
 * on screen, with a text box the player has to type the confirmation word into before the delete
 * button will do anything.
 *
 * <p>The friction is the point. This deletes Dungeon Train world saves and the cross-world profile
 * <em>permanently, with no backup</em>, from a screen two clicks off the title menu — so every world
 * that is about to go is listed by name first, and a mis-click cannot start it.</p>
 *
 * <p>Two phases in one screen: the confirm list, then (after {@link ProfileWipe#execute}) the result
 * list, swapped by re-running {@link #init} via {@code rebuildWidgets}. Scroll/viewport machinery is
 * the same layout-once-into-canvas-space approach as {@link VideoToolsScreen}.</p>
 */
public final class ResetProgressScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD = 10;
    private static final int TOP = 16;
    private static final int SECTION_GAP = 10;
    private static final int SCROLL_STEP = 12;
    private static final int BOX_W = 120;
    private static final int BOX_H = 20;

    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC = 0xFFCACACA;
    /** Anything that is about to be destroyed, or failed to be. */
    private static final int COLOUR_DANGER = 0xFFFF6B6B;
    /** Anything the reset is leaving alone. */
    private static final int COLOUR_SAFE = 0xFF8FD48F;

    private final Screen parent;
    private final ProfileWipe.Survey survey;

    /** Null while confirming; set once the wipe has run, which flips the screen to its result phase. */
    private ProfileWipe.Result result;

    private int colX;
    private int colW;
    private int viewportTop;
    private int viewportBottom;
    private int scrollY;
    private int maxScroll;
    private final List<Line> lines = new ArrayList<>();

    private EditBox confirmBox;
    private Button deleteButton;

    private record Line(FormattedCharSequence text, int canvasY, int colour) {}

    public ResetProgressScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
        this.survey = ProfileWipe.survey();
    }

    @Override
    protected void init() {
        lines.clear();
        confirmBox = null;
        deleteButton = null;
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = add(this.title, 0, lh, COLOUR_HEADER);
        y += 6;
        y = result != null ? layoutResult(y, lh) : layoutConfirm(y, lh);

        int contentHeight = y;
        int rowY = this.height - 28;
        // The confirm phase parks its prompt + text box in a fixed strip above the button row, so the
        // box can never scroll out from under the player mid-type.
        int promptH = result == null && !survey.isEmpty() ? BOX_H + lh + 8 : 0;
        viewportTop = TOP;
        viewportBottom = Math.max(TOP, rowY - 8 - promptH);
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        if (result == null && !survey.isEmpty()) {
            confirmBox = new EditBox(this.font, (this.width - BOX_W) / 2, rowY - BOX_H - 4,
                    BOX_W, BOX_H, tr("confirm_box"));
            confirmBox.setMaxLength(32);
            confirmBox.setResponder(text -> {
                if (deleteButton != null) {
                    deleteButton.active = matchesConfirmWord(text);
                }
            });
            addRenderableWidget(confirmBox);
            setInitialFocus(confirmBox);

            int gap = 4;
            int deleteW = 150;
            int cancelW = 100;
            int rowX = (this.width - (deleteW + gap + cancelW)) / 2;
            deleteButton = Button.builder(tr("do_it"), b -> runWipe())
                    .bounds(rowX, rowY, deleteW, 20)
                    .build();
            deleteButton.active = matchesConfirmWord(confirmBox.getValue());
            addRenderableWidget(deleteButton);
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                    .bounds(rowX + deleteW + gap, rowY, cancelW, 20)
                    .build());
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                    .bounds((this.width - 100) / 2, rowY, 100, 20)
                    .build());
        }
    }

    /** The "here is exactly what goes" list. */
    private int layoutConfirm(int y, int lh) {
        if (survey.isEmpty()) {
            return add(tr("nothing"), y, lh, COLOUR_DESC);
        }

        y = add(tr("warning"), y, lh, COLOUR_DANGER);
        y += SECTION_GAP;

        if (!survey.dtWorlds().isEmpty()) {
            y = add(tr("worlds_header", survey.dtWorlds().size()), y, lh, COLOUR_HEADER);
            for (ProfileWipe.World world : survey.dtWorlds()) {
                y = add(bullet(world.displayName()), y, lh, COLOUR_DANGER);
            }
            y += SECTION_GAP;
        }

        if (!survey.profileItems().isEmpty()) {
            y = add(tr("profile_header"), y, lh, COLOUR_HEADER);
            for (ProfileItem item : survey.profileItems()) {
                y = add(bullet(item.label().getString()), y, lh, COLOUR_DANGER);
            }
            y += SECTION_GAP;
        }

        // Named last and in green: the worlds that survive are the reassurance, not the threat.
        y = add(survey.keptWorlds().isEmpty()
                ? tr("kept_none")
                : tr("kept", survey.keptWorlds().size()), y, lh, COLOUR_SAFE);
        y += SECTION_GAP;
        return add(tr("prompt", confirmWord()), y, lh, COLOUR_DESC);
    }

    /** What actually happened, including anything that refused to go. */
    private int layoutResult(int y, int lh) {
        y = add(tr("done_worlds", result.worldsDeleted()), y, lh, COLOUR_HEADER);
        y = add(tr("done_profile", result.itemsDeleted()), y, lh, COLOUR_HEADER);
        y += SECTION_GAP;
        if (!result.failures().isEmpty()) {
            y = add(tr("failures_header"), y, lh, COLOUR_DANGER);
            for (String failure : result.failures()) {
                y = add(bullet(failure), y, lh, COLOUR_DANGER);
            }
            y += SECTION_GAP;
        }
        return add(tr("done_desc"), y, lh, COLOUR_DESC);
    }

    private void runWipe() {
        result = ProfileWipe.execute(survey);
        scrollY = 0;
        rebuildWidgets();
    }

    /**
     * The typed confirmation, matched case-insensitively against both the translated word and the
     * English {@code DELETE} — so a translated client always has a word that works even if its lang
     * file is stale.
     */
    private boolean matchesConfirmWord(String typed) {
        String value = typed.trim().toLowerCase(Locale.ROOT);
        return !value.isEmpty()
                && (value.equals(confirmWord().toLowerCase(Locale.ROOT)) || value.equals("delete"));
    }

    private String confirmWord() {
        return tr("confirm_word").getString();
    }

    private static MutableComponent tr(String suffix) {
        return Component.translatable("gui.dungeontrain.video_tools.reset." + suffix);
    }

    private static MutableComponent tr(String suffix, Object... args) {
        return Component.translatable("gui.dungeontrain.video_tools.reset." + suffix, args);
    }

    private static Component bullet(String text) {
        return Component.literal("  • " + text);
    }

    private int add(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, colour));
            y += lh;
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            this.scrollY = Mth.clamp(this.scrollY - (int) (scrollY * SCROLL_STEP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);
        for (Line line : lines) {
            int drawY = viewportTop + line.canvasY() - scrollY;
            if (drawY + lh < viewportTop || drawY > viewportBottom) {
                continue; // cull off-viewport lines
            }
            g.drawString(this.font, line.text(), colX, drawY, line.colour(), false);
        }
        g.disableScissor();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
