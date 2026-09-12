package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * <b>Submit a video</b> — paste a link, press Submit, and it goes to the operator's review queue
 * (never straight onto the list). A kind switch above the box says what the link is:
 *
 * <ul>
 *   <li><b>Video</b> — any YouTube / Bilibili / Twitch / Instagram video link.</li>
 *   <li><b>Streamer</b> — the player's own Twitch channel. Streamers are listed by the days they
 *       stream the game, so the page only takes the link while they are live: a checkbox says so,
 *       and Submit without it just says "come back when you're going live" and sends nothing.</li>
 * </ul>
 *
 * <p>On a dev build the relay's dev cap is the operator's own, so there is no checkbox and no queue —
 * either kind is published the moment it is sent, and the Videos page behind is refreshed.</p>
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

    private CycleButton<VideoSubmitter.Kind> kindButton;
    private EditBox field;
    private Checkbox liveBox;
    private Button submit;
    private VideoSubmitter.Kind kind = VideoSubmitter.Kind.VIDEO;
    private String url = "";
    private boolean live;
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
        int fieldY = this.height / 2 - 20;
        int left = this.width / 2 - fieldW / 2;

        // Kind switch above the box; the body text under the title describes whichever is chosen.
        kindButton = addRenderableWidget(CycleButton.<VideoSubmitter.Kind>builder(
                        k -> Component.translatable("gui.dungeontrain.videos.submit.kind." + k.key()))
                .withValues(VideoSubmitter.Kind.values())
                .withInitialValue(kind)
                .create(left, fieldY - FIELD_H - GAP, fieldW, FIELD_H,
                        Component.translatable("gui.dungeontrain.videos.submit.kind"),
                        (b, k) -> setKind(k)));

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

        // The streamer's "I'm live" tick, under the box. Dev builds have no queue to gate, so no box.
        liveBox = Checkbox.builder(Component.translatable("gui.dungeontrain.videos.submit.live"), this.font)
                .pos(left, fieldY + FIELD_H + GAP)
                .selected(live)
                .onValueChange((box, value) -> live = value)
                .build();
        addRenderableWidget(liveBox);

        int buttonsY = fieldY + FIELD_H + GAP + FIELD_H + GAP;
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

    /** Hint and checkbox follow the kind; the URL text survives a switch. */
    private void applyKind() {
        boolean streamer = kind == VideoSubmitter.Kind.STREAMER;
        field.setHint(Component.translatable(streamer
                ? "gui.dungeontrain.videos.submit.hint.streamer" : "gui.dungeontrain.videos.submit.hint"));
        liveBox.visible = streamer && !dev;
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
        if (kind == VideoSubmitter.Kind.STREAMER) {
            // A streamer link must be a bare channel, and — outside dev builds — they must be live
            // now: a marker means "streamed the game today", so an off-air submission records nothing.
            if (!VideoSubmitter.isTwitchChannelUrl(u)) {
                setStatus(Component.translatable("gui.dungeontrain.videos.submit.bad_channel"), ERROR_COLOUR);
                return;
            }
            if (!dev && !live) {
                setStatus(Component.translatable("gui.dungeontrain.videos.submit.not_live"), WARN_COLOUR);
                return;
            }
        }
        sending = true;
        setStatus(Component.translatable("gui.dungeontrain.videos.submit.sending"), BODY_COLOUR);
        refreshSubmit();
        VideoSubmitter.submitAsync(u, kind, live, result -> Minecraft.getInstance().execute(() -> onResult(result)));
    }

    /** Render thread. */
    private void onResult(VideoSubmitter.Result result) {
        sending = false;
        boolean accepted = result == VideoSubmitter.Result.QUEUED || result == VideoSubmitter.Result.PUBLISHED;
        UiAnalytics.confirm(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_SUBMIT, accepted);
        switch (result) {
            case QUEUED -> {
                setStatus(Component.translatable("gui.dungeontrain.videos.submit.queued"), OK_COLOUR);
                clearField();
            }
            case PUBLISHED -> {
                setStatus(Component.translatable("gui.dungeontrain.videos.submit.published"), OK_COLOUR);
                clearField();
                // It is on the list now; the Videos page behind this one should show it on return.
                VideoCatalog.retry();
            }
            case NOT_LIVE -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.not_live"), WARN_COLOUR);
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
        int y = kindButton.getY() - 6 - (body.size() + note.size()) * this.font.lineHeight - (note.isEmpty() ? 0 : 2);
        for (var line : body) {
            g.drawCenteredString(this.font, line, this.width / 2, y, BODY_COLOUR);
            y += this.font.lineHeight;
        }
        y += note.isEmpty() ? 0 : 2;
        for (var line : note) {
            g.drawCenteredString(this.font, line, this.width / 2, y, NOTE_COLOUR);
            y += this.font.lineHeight;
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
