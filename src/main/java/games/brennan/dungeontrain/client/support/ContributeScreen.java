package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Contribute: what every "Support the Mod" button opens (title screen, pause menu, builder pause
 * menu, Credits). Three gift tiers, one featured community reward and See All Rewards, then the
 * open-ended options as a line of links: the shared {@link ContributePanel} on the support screens'
 * panel style.
 *
 * <p>Reports on the {@code support_page} surface ({@code pageOpen} here, {@code pageTime} on close),
 * the surface the old Ways to Help page used, so the Support funnel stays comparable across the
 * change. The currency selector (its own session) goes top right.</p>
 */
public final class ContributeScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD = 10;
    private static final int TOP_MIN = 10;
    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_TITLE = 0xFFFFFFFF;
    private static final int COLOUR_SUB = 0xFF9A9A9A;

    private final Screen parent;
    private final ContributePanel panel;
    private final long openedAtMs = System.currentTimeMillis();
    private boolean timeReported;

    private int colX, colW, top, bottom, titleY, subY;
    private List<FormattedCharSequence> subLines = List.of();

    public ContributeScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.contribute.title"));
        this.parent = parent;
        this.panel = new ContributePanel(this, UiAnalytics.SURFACE_SUPPORT_PAGE, ContributePanel.Style.PAGE,
                this::rebuildWidgets);
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_SUPPORT_PAGE);
        OfficialLinks.ensureFetched();
        CommunityRewardsScreen.refreshSummary(this);
    }

    @Override
    protected void init() {
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;
        subLines = this.font.split(Component.translatable("gui.dungeontrain.contribute.subtitle"), colW);

        int headH = lh + 4 + subLines.size() * (lh + 1) + 8;
        int panelH = panel.measure(this.font, colW);
        int doneH = 8 + 20;
        int total = headH + panelH + doneH;
        top = Math.max(TOP_MIN + PANEL_PAD, (this.height - total) / 2);

        titleY = top;
        subY = titleY + lh + 4;
        int y = top + headH;
        y += panel.layout(this.font, colX, y, colW, this::addRenderableWidget);
        bottom = y;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, y + 8, 200, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, top - PANEL_PAD, colX + colW + PANEL_PAD, bottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, titleY, COLOUR_TITLE);
        int y = subY;
        for (FormattedCharSequence l : subLines) {
            g.drawString(this.font, l, this.width / 2 - this.font.width(l) / 2, y, COLOUR_SUB, false);
            y += this.font.lineHeight + 1;
        }
        panel.render(g, this.font);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && panel.mouseClicked(this.font, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void onClose() {
        if (!timeReported) {
            timeReported = true;
            UiAnalytics.pageTime(UiAnalytics.SURFACE_SUPPORT_PAGE, System.currentTimeMillis() - openedAtMs);
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
