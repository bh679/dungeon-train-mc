package games.brennan.dungeontrain.client.modcheck;

import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Tells the player, once, that their installed music mods are approved but kept off the Live Eye
 * stream: the music isn't heard on it and the lyrics are hidden while they stream. "Don't show again"
 * remembers these mods ({@link LiveFeedClientConfig#addStreamMutedNoticeSeen}); a new one brings the
 * notice back. Opened by {@link StreamMutedModsPopupHandler}. Styled like {@link UnsupportedModsScreen}.
 */
@OnlyIn(Dist.CLIENT)
public final class StreamMutedModsScreen extends Screen {

    /** One installed stream-muted mod. */
    public record MutedMod(String modId, String displayName) {}

    private static final int PANEL_W = 300;
    private static final int PAD = 12;
    private static final int GAP = 6;
    private static final int ROW_H = 12;
    private static final int BUTTON_H = 20;
    private static final int CHECKBOX_H = 20;
    private static final int PANEL_BG = 0xE0101010;
    private static final int PANEL_EDGE = 0xFF505050;
    private static final int ROW_ALT = 0x20FFFFFF;
    private static final int TITLE_COLOUR = 0xFFFFD060;
    private static final int BODY_COLOUR = 0xFFD0D0D0;
    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final int ID_COLOUR = 0xFF909090;

    private final Screen parent;
    private final List<MutedMod> mods;
    private List<FormattedCharSequence> bodyLines = List.of();
    private Checkbox dontShow;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;

    public StreamMutedModsScreen(Screen parent, List<MutedMod> mods) {
        super(Component.translatable("gui.dungeontrain.stream_muted_mods.title"));
        this.parent = parent;
        this.mods = List.copyOf(mods);
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 32);
        int inner = panelW - PAD * 2;
        bodyLines = this.font.split(Component.translatable("gui.dungeontrain.stream_muted_mods.body"), inner);
        int bodyH = bodyLines.size() * (this.font.lineHeight + 1);
        panelH = PAD + this.font.lineHeight + GAP + bodyH + GAP + mods.size() * ROW_H + GAP
            + CHECKBOX_H + GAP + BUTTON_H + PAD;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(8, (this.height - panelH) / 2);
        listY = panelY + PAD + this.font.lineHeight + GAP + bodyH + GAP;

        int checkboxY = listY + mods.size() * ROW_H + GAP;
        dontShow = Checkbox.builder(Component.translatable("gui.dungeontrain.stream_muted_mods.dont_show"), this.font)
            .pos(panelX + PAD, checkboxY)
            .selected(false)
            .build();
        addRenderableWidget(dontShow);
        addRenderableWidget(new DarkTintedButton(panelX + PAD, checkboxY + CHECKBOX_H + GAP, inner, BUTTON_H,
            CommonComponents.GUI_DONE, b -> onClose()));
    }

    @Override
    public void onClose() {
        if (dontShow != null && dontShow.selected()) {
            LiveFeedClientConfig.addStreamMutedNoticeSeen(mods.stream().map(MutedMod::modId).toList());
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title.copy().withStyle(ChatFormatting.BOLD),
            this.width / 2, panelY + PAD, TITLE_COLOUR);
        int y = panelY + PAD + this.font.lineHeight + GAP;
        for (FormattedCharSequence line : bodyLines) {
            g.drawString(this.font, line, panelX + PAD, y, BODY_COLOUR, false);
            y += this.font.lineHeight + 1;
        }
        for (int i = 0; i < mods.size(); i++) {
            int rowY = listY + i * ROW_H;
            if ((i & 1) == 0) g.fill(panelX + PAD / 2, rowY, panelX + panelW - PAD / 2, rowY + ROW_H, ROW_ALT);
            MutedMod mod = mods.get(i);
            String name = "• " + mod.displayName();
            g.drawString(this.font, name, panelX + PAD, rowY + 2, NAME_COLOUR, false);
            g.drawString(this.font, mod.modId(), panelX + PAD + this.font.width(name) + 6, rowY + 2, ID_COLOUR, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, PANEL_EDGE);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
    }
}
