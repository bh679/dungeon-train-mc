package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.relay.BuilderReviewEdits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The comment that goes with a Workbench build's edits when the developer sends them back to its
 * author — what changed and why, which the author reads in My Builds beside Accept and Keep mine.
 *
 * <p>Sends {@code /dungeontrain editor workbench send <id> <comment>}, so the X menu and the slash
 * command are one path with one set of refusals ({@code WorkbenchSendEdits}). A comment is required:
 * an edit with no word on it reads to its author as their build changing under them.</p>
 */
public final class WorkbenchSendEditsScreen extends Screen {

    private static final int FIELD_WIDTH = 300;
    private static final int BOX_HEIGHT = 80;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 6;
    private static final int HEADER_HEIGHT = 40;
    private static final int BOX_PADDING = 4;
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int HINT_COLOUR = 0xFFA0A0A0;
    private static final int PLACEHOLDER_COLOUR = 0xFF707070;

    private final Screen backScreen;
    private final String stagedId;
    private String comment = "";
    private MultiLineEditBox box;
    private Button send;
    private int top;

    private WorkbenchSendEditsScreen(Screen backScreen, String stagedId) {
        super(Component.translatable("gui.dungeontrain.workbench.send.title"));
        this.backScreen = backScreen;
        this.stagedId = stagedId;
    }

    /** Open over the current screen for {@code stagedId}, returning to it afterwards. */
    public static void open(String stagedId) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new WorkbenchSendEditsScreen(mc.screen, stagedId));
    }

    /**
     * The command for {@code comment}: one line (chat refuses line breaks), trimmed and clipped as the
     * relay would. Null when there is nothing to say. Package-private for tests.
     */
    static String command(String stagedId, String comment) {
        String line = comment == null ? "" : comment.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").strip();
        if (line.length() > BuilderReviewEdits.COMMENT_MAX) line = line.substring(0, BuilderReviewEdits.COMMENT_MAX).strip();
        return line.isEmpty() ? null : "dungeontrain editor workbench send " + stagedId + " " + line;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(FIELD_WIDTH, this.width - 32);
        int x = this.width / 2 - fieldWidth / 2;
        this.top = Math.max(8, (this.height - (HEADER_HEIGHT + BOX_HEIGHT + ROW_GAP * 2 + ROW_HEIGHT)) / 2);
        int y = top + HEADER_HEIGHT;
        this.box = new MultiLineEditBox(this.font, x, y, fieldWidth, BOX_HEIGHT,
                Component.translatable("gui.dungeontrain.workbench.send.placeholder"),
                Component.translatable("gui.dungeontrain.workbench.send.title"));
        box.setCharacterLimit(BuilderReviewEdits.COMMENT_MAX);
        box.setValue(comment);
        box.setValueListener(value -> {
            comment = value;
            if (send != null) send.active = command(stagedId, value) != null;
        });
        addRenderableWidget(box);
        setInitialFocus(box);
        y += BOX_HEIGHT + ROW_GAP * 2;
        int half = (fieldWidth - ROW_GAP) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, half, ROW_HEIGHT).build());
        this.send = addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.workbench.send.go"),
                        b -> send())
                .bounds(x + fieldWidth - half, y, half, ROW_HEIGHT).build());
        send.active = command(stagedId, comment) != null;
    }

    private void send() {
        String cmd = command(stagedId, comment);
        if (cmd == null || this.minecraft.player == null) return;
        this.minecraft.player.connection.sendCommand(cmd);
        // Out of the menus altogether: the answer arrives in chat once the relay has replied.
        this.minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, top + 10, TEXT_COLOUR);
        g.drawCenteredString(this.font, Component.literal(stagedId), this.width / 2, top + 24, HINT_COLOUR);
        if (box != null && comment.isEmpty()) {
            // Vanilla draws a box's placeholder only while it is unfocused, and this one opens focused.
            g.drawWordWrap(this.font, Component.translatable("gui.dungeontrain.workbench.send.placeholder"),
                    box.getX() + BOX_PADDING, box.getY() + BOX_PADDING, box.getWidth() - BOX_PADDING * 2, PLACEHOLDER_COLOUR);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(backScreen);
    }
}
