package games.brennan.dungeontrain.client.social;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Options → Account → <b>My socials</b>: the Instagram and Threads accounts on the player's profile, which the
 * official posts tag when they feature the player's photo, run or book ({@link PlayerSocialsRelay}).
 *
 * <p>One row per platform: its logo and name, the handle as a link to the profile (or a <b>Sign in</b> button when it
 * is not added yet), then an @ for Tag me and a bin for Remove, each named in its tooltip and greyed out until there
 * is an account. Signing in happens in the browser, on the platform itself, so the handle is proven to be theirs;
 * the screen says so and offers <b>Refresh</b> for when they come back. Kid mode and Public posts off are both said
 * plainly rather than hidden: the first offers nothing, the second still lets the player set things up.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MySocialsScreen extends Screen {

    private static final int FORM_W = 320;
    private static final int ROW_H = 20;
    private static final int GAP = 6;
    private static final ResourceLocation TRASH_ICON = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/trash");
    private static final int ICON_PX = 16;
    private static final int LOGO_PX = 16;
    /** Logo plus the platform's name, left of the handle. */
    private static final int LABEL_W = 84;
    private static final int TAG_ON = 0x55FF55;
    private static final int TAG_OFF = 0x808080;
    /** The @ and bin of a platform not added yet: there, but faint. */
    private static final float DISABLED_ALPHA = 0.35F;

    private final Screen parent;
    @Nullable
    private PlayerSocialsRelay.Status status;
    private boolean loading = true;
    private boolean busy;
    private Component message = Component.empty();
    private List<FormattedCharSequence> introLines = List.of();
    /** Where each row's logo and platform name are drawn (they are not widgets). */
    private final List<RowLabel> labels = new ArrayList<>();

    private record RowLabel(String platform, int x, int y) {}

    public MySocialsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.socials.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (loading && status == null) refresh();
        build();
    }

    private void build() {
        clearWidgets();
        labels.clear();
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        introLines = font.split(FormattedText.of(intro().getString()), formW);
        int y = 40 + introLines.size() * 10 + GAP;

        if (!ClientDisplayConfig.isKidMode() && status != null) {
            // One row per platform, added or not: logo and name, then the handle as a link to the profile (or a
            // Sign in button), an @ (Tag me, green when on) and a bin (Remove). Without an account the @ and bin
            // stay in place, greyed out, so the row reads the same either way.
            for (String platform : PlayerSocialsRelay.PLATFORM_ORDER) {
                PlayerSocialsRelay.Social s = status.socials().stream()
                    .filter(x -> x.platform().equals(platform)).findFirst().orElse(null);
                labels.add(new RowLabel(platform, left, y));
                int midX = left + LABEL_W;
                int midW = formW - LABEL_W - 2 * ROW_H - 2 * GAP;
                if (s != null) {
                    String profile = PlayerSocialsRelay.profileUrl(platform, s.handle());
                    Component text = Component.literal("@" + s.handle()).withStyle(ChatFormatting.UNDERLINE);
                    PlainTextButton link = new PlainTextButton(midX, y + (ROW_H - 9) / 2, Math.min(midW, font.width(text)), 9, text,
                        b -> { if (profile != null) ConfirmLinkScreen.confirmLinkNow(this, profile); }, font);
                    link.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.socials.open_profile")));
                    addRenderableWidget(link);
                } else {
                    String via = PlayerSocialsRelay.signInFor(platform, status.signIns());
                    Button signIn = addRenderableWidget(new DarkTintedButton(midX, y, midW, ROW_H,
                        Component.translatable("gui.dungeontrain.socials.sign_in"), b -> { if (via != null) signIn(via); }));
                    signIn.setTooltip(Tooltip.create(Component.translatable(via == null ? "gui.dungeontrain.socials.unavailable"
                        : "gui.dungeontrain.socials.sign_in." + via + ".tip")));
                    signIn.active = !busy && via != null;
                }
                boolean on = s != null && s.tag();
                Component tagName = Component.translatable(on ? "gui.dungeontrain.socials.tag_on" : "gui.dungeontrain.socials.tag_off");
                Button tag = addRenderableWidget(new DarkTintedButton(midX + midW + GAP, y, ROW_H, ROW_H,
                    Component.literal("@").withStyle(st -> st.withColor(on ? TAG_ON : TAG_OFF).withBold(true)),
                    b -> run(PlayerSocialsRelay.setTag(platform, !on))));
                tag.setTooltip(Tooltip.create(tagName));
                Component removeName = Component.translatable("gui.dungeontrain.socials.remove");
                Button remove = new FadingIconButton(left + formW - ROW_H, y, ROW_H, TRASH_ICON, removeName,
                    b -> run(PlayerSocialsRelay.remove(platform)));
                remove.setTooltip(Tooltip.create(removeName));
                addRenderableWidget(remove);
                tag.active = remove.active = !busy && s != null;
                if (s == null) {
                    tag.setAlpha(DISABLED_ALPHA);
                    remove.setAlpha(DISABLED_ALPHA);
                }
                y += ROW_H + GAP;
            }
        }

        int half = (formW - GAP) / 2;
        Button refresh = addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.socials.refresh"), b -> refresh())
            .bounds(left, this.height - 30, half, ROW_H).build());
        refresh.active = !busy && !loading;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(left + half + GAP, this.height - 30, formW - half - GAP, ROW_H).build());
    }

    /** What the top of the screen says, for the state it is in. */
    private Component intro() {
        if (ClientDisplayConfig.isKidMode()) return Component.translatable("gui.dungeontrain.socials.kid");
        if (loading) return Component.translatable("gui.dungeontrain.socials.loading");
        if (status == null) return Component.translatable("gui.dungeontrain.socials.failed");
        Component base = Component.translatable(status.socials().isEmpty() ? "gui.dungeontrain.socials.intro.empty" : "gui.dungeontrain.socials.intro");
        return ClientDisplayConfig.isPublicPosts() ? base
            : Component.empty().append(base).append(" ").append(Component.translatable("gui.dungeontrain.socials.public_posts_off"));
    }

    private static ResourceLocation logo(String platform) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/" + platform);
    }

    private static Component platformName(String platform) {
        return Component.translatable("gui.dungeontrain.socials.platform." + platform);
    }

    private void refresh() {
        loading = true;
        PlayerSocialsRelay.fetch().thenAccept(s -> Minecraft.getInstance().execute(() -> {
            loading = false;
            status = s;
            if (this.minecraft != null && this.minecraft.screen == this) build();
        }));
    }

    private void signIn(String via) {
        busy = true;
        message = Component.translatable("gui.dungeontrain.socials.opening");
        build();
        PlayerSocialsRelay.start(via).thenAccept(r -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (r.url() != null) {
                Util.getPlatform().openUri(URI.create(r.url()));
                message = Component.translatable("gui.dungeontrain.socials.opened").withStyle(ChatFormatting.GREEN);
            } else {
                message = failure(r.failure());
            }
            if (this.minecraft != null && this.minecraft.screen == this) build();
        }));
    }

    private void run(java.util.concurrent.CompletableFuture<PlayerSocialsRelay.Result> call) {
        busy = true;
        message = Component.empty();
        build();
        call.thenAccept(r -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (r.status() != null) status = r.status();
            else message = failure(r.failure());
            if (this.minecraft != null && this.minecraft.screen == this) build();
        }));
    }

    private static Component failure(@Nullable PlayerSocialsRelay.Failure f) {
        String key = switch (f == null ? PlayerSocialsRelay.Failure.FAILED : f) {
            case NO_CONSENT -> "gui.dungeontrain.socials.no_consent";
            case NOT_PROVEN -> "gui.dungeontrain.socials.not_proven";
            case UNAVAILABLE -> "gui.dungeontrain.socials.unavailable";
            case FAILED -> "gui.dungeontrain.socials.failed";
        };
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        int y = 40;
        for (FormattedCharSequence line : introLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, 0xAAAAAA);
            y += 10;
        }
        for (RowLabel l : labels) {
            g.blitSprite(logo(l.platform()), l.x(), l.y() + (ROW_H - LOGO_PX) / 2, LOGO_PX, LOGO_PX);
            g.drawString(this.font, platformName(l.platform()), l.x() + LOGO_PX + 5, l.y() + (ROW_H - 8) / 2, 0xFFFFFF);
        }
        if (!message.getString().isEmpty()) {
            g.drawCenteredString(this.font, message, this.width / 2, this.height - 46, 0xFFFFFF);
        }
    }

    /** A square button with a centred sprite that, unlike vanilla's SpriteIconButton, fades its icon with its alpha. */
    private static final class FadingIconButton extends Button {
        private final ResourceLocation icon;

        FadingIconButton(int x, int y, int size, ResourceLocation icon, Component name, OnPress onPress) {
            super(x, y, size, size, name, onPress, DEFAULT_NARRATION);
            this.icon = icon;
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            g.setColor(1.0F, 1.0F, 1.0F, this.alpha);
            g.blitSprite(icon, getX() + (getWidth() - ICON_PX) / 2, getY() + (getHeight() - ICON_PX) / 2, ICON_PX, ICON_PX);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        @Override
        public void renderString(GuiGraphics g, net.minecraft.client.gui.Font font, int color) {
            // Icon only: the name is the tooltip and the narration.
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
