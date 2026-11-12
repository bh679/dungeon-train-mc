package games.brennan.dungeontrain.client.social;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One platform's line in the Account tab's My Socials block: the platform's logo and name, then the handle as a text
 * link to the profile (or a <b>Sign in</b> button when it is not added yet), then an @ for Tag me (green when on) and
 * a bin for Remove, each named in its tooltip. Without an account the @ and bin stay in place, faint and inactive, so
 * every line reads the same. A settings-list row holds one widget, so this one lays out and forwards to its parts.
 */
@OnlyIn(Dist.CLIENT)
public final class SocialRowWidget extends AbstractWidget {

    private static final ResourceLocation TRASH_ICON = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/trash");
    private static final int LOGO_PX = 16;
    /** Logo plus the platform's name, left of the handle. */
    private static final int LABEL_W = 96;
    private static final int GAP = 4;
    private static final int TAG_ON = 0x55FF55;
    private static final int TAG_OFF = 0x808080;
    /** The @ and bin of a platform not added yet: there, but faint. */
    private static final float DISABLED_ALPHA = 0.35F;

    private final Font font;
    private final String platform;
    private final AbstractWidget middle;
    private final Button tag;
    private final Button remove;

    /**
     * @param social the account on this platform, or null when there is none
     * @param via    the sign-in this line's button uses, or null when none is offered
     * @param onChange redraws the tab once a change has an answer
     */
    public SocialRowWidget(Font font, int width, int height, String platform, @Nullable PlayerSocialsRelay.Social social,
                           @Nullable String via, boolean busy, Runnable onChange) {
        super(0, 0, width, height, Component.translatable("gui.dungeontrain.socials.platform." + platform));
        this.font = font;
        this.platform = platform;
        if (social != null) {
            String profile = PlayerSocialsRelay.profileUrl(platform, social.handle());
            Component text = Component.literal("@" + social.handle()).withStyle(ChatFormatting.UNDERLINE);
            PlainTextButton link = new PlainTextButton(0, 0, font.width(text), font.lineHeight, text, b -> {
                Minecraft mc = Minecraft.getInstance();
                if (profile != null && mc.screen != null) ConfirmLinkScreen.confirmLinkNow(mc.screen, profile);
            }, font);
            link.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.socials.open_profile")));
            this.middle = link;
        } else {
            Button signIn = new DarkTintedButton(0, 0, 10, height, Component.translatable("gui.dungeontrain.socials.sign_in"),
                b -> { if (via != null) MySocialsState.signIn(via, onChange); });
            signIn.setTooltip(Tooltip.create(Component.translatable(via == null ? "gui.dungeontrain.socials.unavailable"
                : "gui.dungeontrain.socials.sign_in." + via + ".tip")));
            signIn.active = !busy && via != null;
            this.middle = signIn;
        }
        boolean on = social != null && social.tag();
        this.tag = new DarkTintedButton(0, 0, height, height,
            Component.literal("@").withStyle(st -> st.withColor(on ? TAG_ON : TAG_OFF).withBold(true)),
            b -> MySocialsState.setTag(platform, !on, onChange));
        this.tag.setTooltip(Tooltip.create(Component.translatable(on ? "gui.dungeontrain.socials.tag_on" : "gui.dungeontrain.socials.tag_off")));
        Component removeName = Component.translatable("gui.dungeontrain.socials.remove");
        this.remove = new FadingIconButton(0, 0, height, TRASH_ICON, removeName, b -> MySocialsState.remove(platform, onChange));
        this.remove.setTooltip(Tooltip.create(removeName));
        this.tag.active = this.remove.active = !busy && social != null;
        if (social == null) {
            this.tag.setAlpha(DISABLED_ALPHA);
            this.remove.setAlpha(DISABLED_ALPHA);
        }
    }

    private List<AbstractWidget> parts() {
        return List.of(middle, tag, remove);
    }

    /** Puts the parts where they belong for this frame's position and width. */
    private void layout() {
        int x = getX();
        int y = getY();
        int h = getHeight();
        remove.setPosition(x + getWidth() - h, y);
        tag.setPosition(remove.getX() - GAP - h, y);
        int midX = x + LABEL_W;
        if (middle instanceof PlainTextButton) {
            middle.setPosition(midX, y + (h - font.lineHeight) / 2);
            middle.setWidth(Math.min(middle.getWidth(), tag.getX() - GAP - midX));
        } else {
            middle.setPosition(midX, y);
            middle.setWidth(tag.getX() - GAP - midX);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        g.blitSprite(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/" + platform),
            getX(), getY() + (getHeight() - LOGO_PX) / 2, LOGO_PX, LOGO_PX);
        g.drawString(font, getMessage(), getX() + LOGO_PX + 5, getY() + (getHeight() - font.lineHeight) / 2 + 1, 0xFFFFFF);
        for (AbstractWidget w : parts()) w.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        layout();
        for (AbstractWidget w : parts()) {
            if (w.mouseClicked(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        out.add(NarratedElementType.TITLE, getMessage());
    }
}
