package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * <b>Flag this video</b> — three reasons, one press each: <i>Not about Dungeon Train</i>, <i>Not
 * safe for kids</i>, or <i>Something else</i>, which opens a box for the player's own words and a
 * Send button. The video's title is shown so there is no doubt what is being reported.
 *
 * <p>The two fixed reasons send immediately — a confirmation step would be a second click for a
 * choice that already took one. The status line says what the relay did: recorded, or removed from
 * this list (the row disappears from the Videos page behind), or too many, or unreachable. Back
 * returns to the list either way.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideoFlagScreen extends Screen {

    private static final int BUTTON_W = 220;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;
    private static final int MAX_TEXT = 300;
    private static final int TITLE_COLOUR = 0xFFFFFFFF;
    private static final int BODY_COLOUR = 0xFFC8C8C8;
    private static final int OK_COLOUR = 0xFF7FDD7F;
    private static final int ERROR_COLOUR = 0xFFCF5C5C;

    private final VideosScreen parent;
    private final VideoEntry video;

    private Button notDt;
    private Button nsfk;
    private Button custom;
    private EditBox customText;
    private Button send;
    private String text = "";
    private boolean customOpen;
    private boolean sending;
    private boolean done;
    private Component status;
    private int statusColour = BODY_COLOUR;

    public VideoFlagScreen(VideosScreen parent, VideoEntry video) {
        super(Component.translatable("gui.dungeontrain.videos.flag.title"));
        this.parent = parent;
        this.video = video;
    }

    @Override
    protected void init() {
        int w = Math.min(BUTTON_W, this.width - 32);
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 2 * BUTTON_H - GAP;

        notDt = addRenderableWidget(new DarkTintedButton(x, y, w, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.flag.reason.not_dt"), b -> send(VideoFlagger.Reason.NOT_DT)));
        y += BUTTON_H + GAP;
        nsfk = addRenderableWidget(new DarkTintedButton(x, y, w, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.flag.reason.nsfk"), b -> send(VideoFlagger.Reason.NSFK)));
        y += BUTTON_H + GAP;
        custom = addRenderableWidget(new DarkTintedButton(x, y, w, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.flag.reason.custom"), b -> {
                    customOpen = true;
                    refresh();
                    setFocused(customText);
                }));
        y += BUTTON_H + GAP;

        // The custom row: a box and a Send, shown only once "Something else" is picked.
        int sendW = 60;
        customText = new EditBox(this.font, x, y, w - sendW - GAP, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.flag.custom.field"));
        customText.setMaxLength(MAX_TEXT);
        customText.setValue(text);
        customText.setHint(Component.translatable("gui.dungeontrain.videos.flag.custom.hint"));
        customText.setResponder(t -> {
            text = t;
            refresh();
        });
        addRenderableWidget(customText);
        send = addRenderableWidget(new DarkTintedButton(x + w - sendW, y, sendW, BUTTON_H,
                Component.translatable("gui.dungeontrain.videos.flag.custom.send"), b -> send(VideoFlagger.Reason.CUSTOM)));
        y += BUTTON_H + GAP;

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 16 - BUTTON_H, 100, BUTTON_H)
                .build());
        refresh();
    }

    private void refresh() {
        boolean live = !sending && !done;
        notDt.active = live;
        nsfk.active = live;
        custom.active = live && !customOpen;
        customText.visible = customOpen;
        send.visible = customOpen;
        send.active = live && !text.isBlank();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && customOpen && send.active && customText.isFocused()) {
            send(VideoFlagger.Reason.CUSTOM);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void send(VideoFlagger.Reason reason) {
        if (sending || done) return;
        sending = true;
        setStatus(Component.translatable("gui.dungeontrain.videos.flag.sending"), BODY_COLOUR);
        refresh();
        VideoFlagger.flagAsync(video, reason, text, outcome ->
                Minecraft.getInstance().execute(() -> onOutcome(outcome)));
    }

    /** Render thread. */
    private void onOutcome(VideoFlagger.Outcome outcome) {
        sending = false;
        UiAnalytics.confirm(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_FLAG, outcome.recorded());
        if (outcome.recorded()) {
            done = true;
            VideoCatalog.markFlagged(video.id());
            // Off this player's list now: hidden for everyone, or off the kid list on a kid-mode client.
            boolean gone = outcome.hidden()
                    || (outcome.kidsHidden() && ClientDisplayConfig.getContentMode().isKid());
            if (gone) {
                VideoCatalog.remove(video.id());
                parent.onCatalogChanged();
                setStatus(Component.translatable("gui.dungeontrain.videos.flag.removed"), OK_COLOUR);
            } else {
                setStatus(Component.translatable("gui.dungeontrain.videos.flag.recorded"), OK_COLOUR);
            }
        } else if (outcome.rateLimited()) {
            setStatus(Component.translatable("gui.dungeontrain.videos.flag.rate_limited"), ERROR_COLOUR);
        } else {
            setStatus(Component.translatable("gui.dungeontrain.videos.flag.failed"), ERROR_COLOUR);
        }
        refresh();
    }

    private void setStatus(Component c, int colour) {
        status = c;
        statusColour = colour;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, TITLE_COLOUR);

        // Which video, above the choices — the title ellipsised to the column, the uploader under it.
        int w = Math.min(BUTTON_W + 60, this.width - 32);
        int y = notDt.getY() - 8 - this.font.lineHeight * 2;
        g.drawCenteredString(this.font, this.font.plainSubstrByWidth(video.displayTitle(), w), this.width / 2, y, TITLE_COLOUR);
        if (video.hasChannel()) {
            g.drawCenteredString(this.font, this.font.plainSubstrByWidth(video.channel(), w), this.width / 2,
                    y + this.font.lineHeight, BODY_COLOUR);
        }

        if (status != null) {
            int sy = send.getY() + BUTTON_H + 10;
            for (var line : this.font.split(status, w)) {
                g.drawCenteredString(this.font, line, this.width / 2, sy, statusColour);
                sy += this.font.lineHeight;
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
