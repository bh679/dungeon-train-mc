package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.support.ContributePanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Full-screen "Contribute" window opened from the death-screen donation page's Contribute button:
 * the shared {@link ContributePanel} (three gift tiers, one featured community reward with See All
 * Rewards, the open-ended options as a line of links) on the engine room's dark blue. The same
 * chooser the Contribute page shows from "Support the Mod", so both always offer the same gifts in
 * the same words. Back returns to the death screen it was opened from.
 *
 * <p>Two clients see something different, both handled inside the panel: Simplified-Chinese clients
 * lose the open-ended options (Revolut and Patreon are walled there; the Stripe tiers work), and a
 * client whose relay never served a checkout route gets the open-ended options as its main row.</p>
 */
public final class DonationOptionsScreen extends Screen {

    // Match the death-screen backdrop (NarrativeDeathScreen.OVERLAY) for a seamless hand-off.
    private static final int OVERLAY = 0xF2070C1E;
    private static final int TITLE   = 0xFFE0B56A;
    private static final int NARR    = 0xFFC7BDA7;

    private final Screen parent;
    private final ContributePanel panel;
    private int titleY;
    private int subtitleTop;
    private List<FormattedCharSequence> subtitleLines = List.of();

    public DonationOptionsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.death.narr.donate_button"));
        this.parent = parent;
        this.panel = new ContributePanel(this, UiAnalytics.SURFACE_DEATH_SCREEN, ContributePanel.Style.DEATH,
                this::rebuildWidgets);
        // Distinct funnel step from "viewed the DONATE page": the player opened the Contribute window.
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_DEATH_SCREEN, UiAnalytics.PAGE_CONTRIBUTE);
        OfficialLinks.ensureFetched();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int lineH = this.font.lineHeight + 2;
        int rowW = Math.min(340, this.width - 60);
        subtitleLines = this.font.split(Component.translatable("gui.dungeontrain.contribute.subtitle"), rowW);

        int panelH = panel.measure(this.font, rowW);
        // A short window (240 px in the smallest GUI scale) cannot fit everything: the subtitle goes
        // first, so the gifts and Back always stay on screen.
        if (lineH + 6 + subtitleLines.size() * lineH + 8 + panelH + 8 + 20 + 6 > this.height) subtitleLines = List.of();
        int subH = subtitleLines.size() * lineH;
        int blockH = lineH + 6 + subH + 8 + panelH + 8 + 20;
        int top = Math.max(6, (this.height - blockH) / 2);

        titleY = top;
        subtitleTop = titleY + lineH + 6;
        int y = subtitleTop + subH + 8;
        y += panel.layout(this.font, cx - rowW / 2, y, rowW, this::addRenderableWidget);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(cx - 100, y + 8, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // background + widgets
        int cx = this.width / 2;
        int lineH = this.font.lineHeight + 2;
        g.drawCenteredString(this.font, this.title, cx, titleY, TITLE);
        int y = subtitleTop;
        for (FormattedCharSequence line : subtitleLines) {
            g.drawString(this.font, line, cx - this.font.width(line) / 2, y, NARR, false);
            y += lineH;
        }
        panel.render(g, this.font);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && panel.mouseClicked(this.font, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, OVERLAY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
