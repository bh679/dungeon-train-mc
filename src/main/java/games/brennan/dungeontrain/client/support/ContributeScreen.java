package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.support.currency.CurrencyPicker;
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
 * menu, Credits). Deliberately the same screen as the death screen's Contribute window
 * ({@code DonationOptionsScreen}): the engine room's dark blue, gold title, the shared
 * {@link ContributePanel} in its {@link ContributePanel.Style#DEATH} look, and Back. Brennan chose
 * that look for both, so a player sees one Contribute wherever they open it.
 *
 * <p>Only the analytics differ: this one reports on the {@code support_page} surface
 * ({@code pageOpen} here, {@code pageTime} on close), the surface the old Ways to Help page used, so
 * the Support funnel stays comparable across the change.</p>
 */
public final class ContributeScreen extends Screen implements games.brennan.dungeontrain.client.links.RefreshOnLinks {

    // The death screen's Contribute window (DonationOptionsScreen), matched exactly.
    private static final int OVERLAY = 0xF2070C1E;
    private static final int TITLE = 0xFFE0B56A;
    private static final int NARR = 0xFFC7BDA7;

    private final Screen parent;
    private final ContributePanel panel;
    private final CurrencyPicker currency;
    private final long openedAtMs = System.currentTimeMillis();
    private boolean timeReported;

    private int titleY;
    private int subtitleTop;
    private List<FormattedCharSequence> subtitleLines = List.of();

    public ContributeScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.contribute.title"));
        this.parent = parent;
        this.panel = new ContributePanel(this, UiAnalytics.SURFACE_SUPPORT_PAGE, ContributePanel.Style.DEATH,
                this::rebuildWidgets);
        this.currency = new CurrencyPicker(UiAnalytics.SURFACE_SUPPORT_PAGE, this::rebuildWidgets);
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_SUPPORT_PAGE);
        OfficialLinks.ensureFetched();
        CommunityRewardsScreen.refreshSummary(this);
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
        // The currency button sits at the column's top right, level with the title.
        currency.layout(this.font, cx + rowW / 2, titleY - 3, this.height, this::addRenderableWidget);
        subtitleTop = titleY + lineH + 6;
        int y = subtitleTop + subH + 8;
        y += panel.layout(this.font, cx - rowW / 2, y, rowW, this::addRenderableWidget);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(cx - 100, y + 8, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int lineH = this.font.lineHeight + 2;
        g.drawCenteredString(this.font, this.title, cx, titleY, TITLE);
        int y = subtitleTop;
        for (FormattedCharSequence line : subtitleLines) {
            g.drawString(this.font, line, cx - this.font.width(line) / 2, y, NARR, false);
            y += lineH;
        }
        panel.render(g, this.font);
        currency.render(g, this.font, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (currency.mouseClicked(mx, my, button)) return true;
        if (button == 0 && panel.mouseClicked(this.font, mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return currency.mouseScrolled(mx, my, dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return currency.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, OVERLAY);
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
