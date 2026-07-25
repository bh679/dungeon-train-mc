package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.ColorTintedButton;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Full-screen "Contribute" window opened from the death-screen donation page's Contribute button.
 * Offers the two ways to give — a direct donation (Revolut, name-tagged) and Patreon — each opened
 * through vanilla's {@link ConfirmLinkScreen} with support-funnel analytics, returning here so the
 * player can follow more than one. Closing returns to the death screen it was opened from.
 *
 * <p>Styled to match the death screen: the same dark-blue backdrop, an amber title and muted
 * narration over the two coloured buttons. URLs come from {@link OfficialLinks} (relay-served,
 * baked fallbacks offline).</p>
 */
public final class DonationOptionsScreen extends Screen {

    // Match the death-screen backdrop (NarrativeDeathScreen.OVERLAY) for a seamless hand-off.
    private static final int OVERLAY = 0xF2070C1E;
    private static final int TITLE   = 0xFFE0B56A;
    private static final int NARR    = 0xFFC7BDA7;
    private static final float[] TINT_GREEN  = {0.30F, 0.80F, 0.35F}; // direct donation
    private static final float[] TINT_ORANGE = {1.00F, 0.47F, 0.38F}; // Patreon

    private final Screen parent;
    private int subtitleTop;
    private List<FormattedCharSequence> subtitleLines = List.of();

    public DonationOptionsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.death.narr.donate_button"));
        this.parent = parent;
        UiAnalytics.pageOpen(UiAnalytics.SURFACE_DEATH_SCREEN);
        OfficialLinks.ensureFetched();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bw = Math.min(240, this.width - 80);
        int bx = cx - bw / 2;

        int wrapW = Math.min(360, this.width - 80);
        subtitleLines = this.font.split(
                Component.translatable("gui.dungeontrain.death.narr.donate_options_sub"), wrapW);

        // Title + subtitle sit above the buttons; the button block is vertically centred.
        int y = this.height / 2 - 24;
        subtitleTop = y - 12 - subtitleLines.size() * (this.font.lineHeight + 2);

        ColorTintedButton revolut = new ColorTintedButton(bx, y, bw, 20,
                Component.translatable("gui.dungeontrain.death.narr.donate_revolut"),
                TINT_GREEN[0], TINT_GREEN[1], TINT_GREEN[2],
                b -> openLink(revolutUrl(), UiAnalytics.TARGET_DONATE));
        revolut.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.death.narr.donate_revolut_tip")));
        addRenderableWidget(revolut);
        y += 26;

        addRenderableWidget(new ColorTintedButton(bx, y, bw, 20,
                Component.translatable("gui.dungeontrain.death.narr.donate_patreon"),
                TINT_ORANGE[0], TINT_ORANGE[1], TINT_ORANGE[2],
                b -> openLink(OfficialLinks.patreon(), UiAnalytics.TARGET_PATREON)));
        y += 34;

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(cx - 100, y, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // background + widgets
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, subtitleTop - this.font.lineHeight - 4, TITLE);
        int y = subtitleTop;
        for (FormattedCharSequence line : subtitleLines) {
            g.drawString(this.font, line, cx - this.font.width(line) / 2, y, NARR, false);
            y += this.font.lineHeight + 2;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, OVERLAY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Open {@code url} through the confirm screen, tracking the click + follow-through, then return here. */
    private void openLink(String url, String analyticsTarget) {
        UiAnalytics.click(UiAnalytics.SURFACE_DEATH_SCREEN, analyticsTarget);
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_DEATH_SCREEN, analyticsTarget, yes);
            if (yes) Util.getPlatform().openUri(URI.create(url));
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    /** Direct-donation URL with the player's name URL-encoded onto the Revolut {@code note=} tag. */
    private static String revolutUrl() {
        String base = OfficialLinks.payment();
        if (!base.contains("note=")) return base;
        String encoded = URLEncoder.encode(playerName(), StandardCharsets.UTF_8).replace("+", "%20");
        return base + encoded;
    }

    private static String playerName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() != null ? mc.getUser().getName() : "Player";
    }
}
