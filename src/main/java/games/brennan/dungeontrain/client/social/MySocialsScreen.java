package games.brennan.dungeontrain.client.social;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
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
import java.util.List;

/**
 * Options → Account → <b>My socials</b>: the Instagram and Threads accounts on the player's profile, which the
 * official posts tag when they feature the player's photo, run or book ({@link PlayerSocialsRelay}).
 *
 * <p>One row per account (its handle, which opens the profile, then an @ for Tag me and a bin for Remove, each named
 * in its tooltip), then a <b>Sign in with …</b> button per sign-in
 * the relay offers. Signing in happens in the browser, on the platform itself, so the handle is proven to be theirs;
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
    private static final int TAG_ON = 0x55FF55;
    private static final int TAG_OFF = 0x808080;

    private final Screen parent;
    @Nullable
    private PlayerSocialsRelay.Status status;
    private boolean loading = true;
    private boolean busy;
    private Component message = Component.empty();
    private List<FormattedCharSequence> introLines = List.of();

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
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        introLines = font.split(FormattedText.of(intro().getString()), formW);
        int y = 40 + introLines.size() * 10 + GAP;

        if (!ClientDisplayConfig.isKidMode() && status != null) {
            for (PlayerSocialsRelay.Social s : status.socials()) {
                // The handle opens the profile; then an @ (Tag me, green when on) and a bin (Remove), names in tooltips.
                int handleW = formW - 2 * ROW_H - 2 * GAP;
                String profile = PlayerSocialsRelay.profileUrl(s.platform(), s.handle());
                Button handle = addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.socials.row",
                        platformName(s.platform()), s.handle()), b -> {
                        if (profile != null) ConfirmLinkScreen.confirmLinkNow(this, profile);
                    })
                    .bounds(left, y, handleW, ROW_H).build());
                handle.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.socials.open_profile")));
                handle.active = profile != null;
                Component tagName = Component.translatable(s.tag() ? "gui.dungeontrain.socials.tag_on" : "gui.dungeontrain.socials.tag_off");
                Button tag = addRenderableWidget(new DarkTintedButton(left + handleW + GAP, y, ROW_H, ROW_H,
                    Component.literal("@").withStyle(st -> st.withColor(s.tag() ? TAG_ON : TAG_OFF).withBold(true)),
                    b -> run(PlayerSocialsRelay.setTag(s.platform(), !s.tag()))));
                tag.setTooltip(Tooltip.create(tagName));
                tag.active = !busy;
                Component removeName = Component.translatable("gui.dungeontrain.socials.remove");
                SpriteIconButton remove = SpriteIconButton.builder(removeName, b -> run(PlayerSocialsRelay.remove(s.platform())), true)
                    .width(ROW_H).sprite(TRASH_ICON, ICON_PX, ICON_PX).build();
                remove.setPosition(left + formW - ROW_H, y);
                remove.setTooltip(Tooltip.create(removeName));
                addRenderableWidget(remove).active = !busy;
                y += ROW_H + GAP;
            }
            y += GAP;
            for (String via : status.signIns()) {
                Button signIn = addRenderableWidget(new DarkTintedButton(left, y, formW, ROW_H,
                    Component.translatable("gui.dungeontrain.socials.sign_in." + via), b -> signIn(via)));
                signIn.active = !busy;
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
        if (!message.getString().isEmpty()) {
            g.drawCenteredString(this.font, message, this.width / 2, this.height - 46, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
