package games.brennan.dungeontrain.client.videos;

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
 * <b>Submit a video</b> — paste a link, press Submit, and it goes to the operator's review queue
 * (never straight onto the list). One box, two buttons, and a status line that says exactly what
 * the relay said: queued, already listed, already waiting, not a video link, too many, or unreachable.
 *
 * <p>The box is checked before anything is sent — {@code http(s)://} and no spaces — so an obvious
 * typo is caught without a round trip; the relay does the real parsing and its {@code bad_url} is
 * shown the same way. Submit is disabled while a request is in flight.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideoSubmitScreen extends Screen {

    private static final int FIELD_W = 300;
    private static final int FIELD_H = 20;
    private static final int BUTTON_W = 100;
    private static final int GAP = 4;
    private static final int MAX_URL = 500;
    private static final int BODY_COLOUR = 0xFFC8C8C8;
    private static final int OK_COLOUR = 0xFF7FDD7F;
    private static final int WARN_COLOUR = 0xFFE0B56A;
    private static final int ERROR_COLOUR = 0xFFCF5C5C;

    private final Screen parent;

    private EditBox field;
    private Button submit;
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
        int fieldY = this.height / 2 - 20;
        field = new EditBox(this.font, this.width / 2 - fieldW / 2, fieldY, fieldW, FIELD_H,
                Component.translatable("gui.dungeontrain.videos.submit.field"));
        field.setMaxLength(MAX_URL);
        field.setValue(url);
        field.setHint(Component.translatable("gui.dungeontrain.videos.submit.hint"));
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
        refreshSubmit();
    }

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
        sending = true;
        setStatus(Component.translatable("gui.dungeontrain.videos.submit.sending"), BODY_COLOUR);
        refreshSubmit();
        VideoSubmitter.submitAsync(u, result -> Minecraft.getInstance().execute(() -> onResult(result)));
    }

    /** Render thread. */
    private void onResult(VideoSubmitter.Result result) {
        sending = false;
        boolean accepted = result == VideoSubmitter.Result.QUEUED;
        UiAnalytics.confirm(UiAnalytics.SURFACE_VIDEOS, UiAnalytics.TARGET_VIDEO_SUBMIT, accepted);
        switch (result) {
            case QUEUED -> {
                setStatus(Component.translatable("gui.dungeontrain.videos.submit.queued"), OK_COLOUR);
                url = "";
                field.setValue("");
            }
            case ALREADY_LISTED -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.already_listed"), WARN_COLOUR);
            case ALREADY_PENDING -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.already_pending"), WARN_COLOUR);
            case BAD_URL -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.bad_url"), ERROR_COLOUR);
            case RATE_LIMITED -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.rate_limited"), ERROR_COLOUR);
            case FAILED -> setStatus(Component.translatable("gui.dungeontrain.videos.submit.failed"), ERROR_COLOUR);
        }
        refreshSubmit();
    }

    private void setStatus(Component text, int colour) {
        status = text;
        statusColour = colour;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        // What this does, above the box: it goes to a person, not onto the page.
        int wrapW = Math.min(FIELD_W, this.width - 32);
        List<net.minecraft.util.FormattedCharSequence> body = this.font.split(
                Component.translatable("gui.dungeontrain.videos.submit.body"), wrapW);
        int y = field.getY() - 6 - body.size() * this.font.lineHeight;
        for (var line : body) {
            g.drawCenteredString(this.font, line, this.width / 2, y, BODY_COLOUR);
            y += this.font.lineHeight;
        }

        if (status != null) {
            int sy = field.getY() + FIELD_H + 8 + FIELD_H + 8;
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
