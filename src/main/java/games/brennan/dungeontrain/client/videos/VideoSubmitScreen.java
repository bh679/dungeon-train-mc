package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * <b>Submit a video</b> — paste a link, press Submit. A two-button switch above the box says what
 * the link is, both halves always visible and the active one framed:
 *
 * <ul>
 *   <li><b>Video</b> — any YouTube / Bilibili / Twitch / Instagram video link. Goes to the operator's
 *       review queue (never straight onto the list).</li>
 *   <li><b>Livestream</b> — the player's own Twitch channel, submitted <em>while live</em>. Under the
 *       switch a pulsing green dot says "Currently live" and a grey line gives the one rule: the
 *       stream title must include "Dungeon Train". The relay checks both on Twitch and, if they hold,
 *       lists the streamer at once with a live dot; otherwise it says why. Nothing to tick.</li>
 * </ul>
 *
 * <p>On a dev build the relay's dev cap is the operator's own, so videos skip the queue too and the
 * Videos page behind is refreshed on success.</p>
 *
 * <p>The box is checked before anything is sent — {@code http(s)://} and no spaces, and for a
 * streamer a bare {@code twitch.tv/<login>} — so an obvious typo is caught without a round trip;
 * the relay does the real parsing and its {@code bad_url} is shown the same way. Submit is
 * disabled while a request is in flight.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideoSubmitScreen extends Screen {

    private static final int FIELD_W = 300;
    private static final int FIELD_H = 20;
    private static final int BUTTON_W = 100;
    private static final int GAP = 4;
    private static final int MAX_URL = 500;
    private static final int BODY_COLOUR = 0xFFC8C8C8;
    private static final int NOTE_COLOUR = 0xFF9A9A9A;
    private static final int OK_COLOUR = 0xFF7FDD7F;
    private static final int WARN_COLOUR = 0xFFE0B56A;
    private static final int ERROR_COLOUR = 0xFFCF5C5C;

    private final Screen parent;
    private final boolean dev = DungeonTrain.isDevBuild();

    /** Height of the "Currently live" + title-rule lines under the switch in Livestream mode. */
    private static final int LIVE_LINES_H = 22;
    private static final int LIVE_DOT = 6;

    private Button videoButton;
    private Button liveButton;
    private EditBox field;
    private Button submit;
    private VideoSubmitter.Kind kind = VideoSubmitter.Kind.VIDEO;
    private String url = "";
    private boolean sending;
    private Component status;
    private int statusColour = BODY_COLOUR;

    public VideoSubmitScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.videos.submit.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int fieldW = Math.min(FIELD_W, this.width - 32);
        int fieldY = this.height / 2 - 12;
        int left = this.width / 2 - fieldW / 2;

        // Kind switch above the box — two halves, both visible, the active one framed. The two
        // "Currently live" lines sit between the switch and the box, so the box is placed for them.
        int halfW = (fieldW - GAP) / 2;
        int switchY = fieldY - LIVE_LINES_H - GAP - FIELD_H;
        videoButton = addRenderableWidget(new KindSegmentButton(left, switchY, halfW, FIELD_H,
                Component.translatable("gui.dungeontrain.videos.submit.kind.video"),
                () -> kind == VideoSubmitter.Kind.VIDEO, b -> setKind(VideoSubmitter.Kind.VIDEO)));
        liveButton = addRenderableWidget(new KindSegmentButton(left + halfW + GAP, switchY, fieldW - halfW - GAP, FIELD_H,
                Component.translatable("gui.dungeontrain.videos.submit.kind.livestream"),
                () -> kind == VideoSubmitter.Kind.STREAMER, b -> setKind(VideoSubmitter.Kind.STREAMER)));

        field = new EditBox(this.font, left, fieldY, fieldW, FIELD_H,
                Component.translatable("gui.dungeontrain.videos.submit.field"));
        field.setMaxLength(MAX_URL);
        field.setValue(url);
        field.setResponder(text -> {
            url = text;
            refreshSubmit();
        });
        addRenderableWidget(field);
        setInitialFocus(field);

        int buttonsY = fieldY + FIELD_H + 8;
        int totalW = 2 * BUTTON_W + GAP;
        submit = addRenderableWidget(new DarkTintedButton(this.width / 2 - totalW / 2, buttonsY, BUTTON_W, FIELD_H,
                Component.translatable("gui.dungeontrain.videos.submit.button"), b -> send()));
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(this.width / 2 - totalW / 2 + BUTTON_W + GAP, buttonsY, BUTTON_W, FIELD_H)
                .build());
        applyKind();
        refreshSubmit();
    }

    private void setKind(VideoSubmitter.Kind k) {
        kind = k;
        status = null;
        applyKind();
        refreshSubmit();
    }

    /** The hint follows the kind; the URL text survives a switch. */
    private void applyKind() {
        boolean streamer = kind == VideoSubmitter.Kind.STREAMER;
        field.setHint(Component.translatable(streamer
                ? "gui.dungeontrain.videos.submit.hint.streamer" : "gui.dungeontrain.videos.submit.hint"));
    }

    /**
     * Submit lights up for any http(s) URL, whatever the kind: the streamer-specific checks happen on
     * the click so the player is told <em>why</em> rather than facing a dead button.
     */
    private void refreshSubmit() {
        if (submit != null) submit.active = !sending && VideoCatalogFetcher.isValidUrl(url);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && submit.active) {   // Enter / numpad Enter
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void send() {
        String u = url.trim();
        if (sending || !VideoCatalogFetcher.isValidUrl(u)) return;
        if (kind == VideoSubmitter.Kind.STREAMER && !VideoSubmitter.isTwitchChannelUrl(u)) {
            // A streamer link must be a bare channel; whether they are live is the relay's check.
            setStatus(Component.translatable("gui.dungeontrain.videos.submit.bad_channel"), ERROR_COLOUR);
            return;
        }
        sending = true;
        setStatus(Component.translatable("gui.dungeontrain.videos.submit.sending"), BODY_COLOUR);
        refreshSubmit();
        VideoSubmitter.submitAsync(u, kind, result -> Minecraft.getInstance().execute(() -> onResult(result)));
    }

    /** Render thread. */
    private void onResult(VideoSubmitter.Result result) {
        sending = false;
        boolean accepted = result == VideoSubmitter.Result.QUEUED || result == VideoSubmitter.Result.PUBLISHED
                || result == VideoSubmitter.Result.LIVE;
        UiAnalytics.confirm(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_SUBMIT, accepted);
        switch (result) {
            case QUEUED -> {
                setStatus(Component.translatable("gui.dungeontrain.videos.submit.queued"), OK_COLOUR);
                clearField();
            }
            case PUBLISHED, LIVE -> {
                setStatus(Component.translatable(kind == VideoSubmitter.Kind.STREAMER
                        ? "gui.dungeontrain.videos.submit.live_ok" : "gui.dungeontrain.videos.submit.published"), OK_COLOUR);
                clearField();
                // It is on the list now; the Videos page behind this one should show it on return.
                VideoCatalog.retry();
            }
            case NOT_LIVE -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.not_live"), WARN_COLOUR);
            case TITLE_MISSING -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.title_missing"), WARN_COLOUR);
            case LIVE_CHECK_UNAVAILABLE -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.live_unavailable"), ERROR_COLOUR);
            case ALREADY_LISTED -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.already_listed"), WARN_COLOUR);
            case ALREADY_PENDING -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.already_pending"), WARN_COLOUR);
            case BAD_URL -> setStatus(Component.translatable(kind == VideoSubmitter.Kind.STREAMER
                    ? "gui.dungeontrain.videos.submit.bad_channel" : "gui.dungeontrain.videos.submit.bad_url"), ERROR_COLOUR);
            case RATE_LIMITED -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.rate_limited"), ERROR_COLOUR);
            case FAILED -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.failed"), ERROR_COLOUR);
        }
        refreshSubmit();
    }

    private void clearField() {
        url = "";
        field.setValue("");
    }

    private void setStatus(Component text, int colour) {
        status = text;
        statusColour = colour;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        // What this does, above the kind switch: it goes to a person, not onto the page — unless
        // this is a dev build, which says so.
        int wrapW = Math.min(FIELD_W, this.width - 32);
        boolean streamer = kind == VideoSubmitter.Kind.STREAMER;
        List<net.minecraft.util.FormattedCharSequence> body = this.font.split(
                Component.translatable(streamer
                        ? "gui.dungeontrain.videos.submit.body.streamer" : "gui.dungeontrain.videos.submit.body"), wrapW);
        List<net.minecraft.util.FormattedCharSequence> note = dev
                ? this.font.split(Component.translatable("gui.dungeontrain.videos.submit.dev_note"), wrapW) : List.of();
        int y = videoButton.getY() - 6 - (body.size() + note.size()) * this.font.lineHeight - (note.isEmpty() ? 0 : 2);
        for (var line : body) {
            g.drawCenteredString(this.font, line, this.width / 2, y, BODY_COLOUR);
            y += this.font.lineHeight;
        }
        y += note.isEmpty() ? 0 : 2;
        for (var line : note) {
            g.drawCenteredString(this.font, line, this.width / 2, y, NOTE_COLOUR);
            y += this.font.lineHeight;
        }

        // Livestream: "● Currently live" and the title rule, between the switch and the box.
        if (streamer) {
            int ly = videoButton.getY() + FIELD_H + GAP;
            Component liveLine = Component.translatable("gui.dungeontrain.videos.submit.currently_live");
            int lw = LIVE_DOT + 4 + this.font.width(liveLine);
            int lx = this.width / 2 - lw / 2;
            VideoList.drawLiveDot(g, lx, ly + (this.font.lineHeight - LIVE_DOT) / 2, LIVE_DOT);
            g.drawString(this.font, liveLine, lx + LIVE_DOT + 4, ly, VideoList.LIVE_COLOUR);
            g.drawCenteredString(this.font, Component.translatable("gui.dungeontrain.videos.submit.title_rule"),
                    this.width / 2, ly + this.font.lineHeight + 2, NOTE_COLOUR);
        }

        if (status != null) {
            int sy = submit.getY() + FIELD_H + 8;
            for (var line : this.font.split(status, wrapW)) {
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
