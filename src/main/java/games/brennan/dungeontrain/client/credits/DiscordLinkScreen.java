package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;
import java.util.function.Supplier;

/**
 * "Link your Discord" — the screen behind the Community card's footer. Tells the relay to mint a
 * short code for this player ({@link CommunityLinkClient#start}), shows it large, and waits: the
 * player runs {@code /dtlink <code>} in the Dungeon Train Discord, the relay's bot sees who ran it,
 * and from then on the relay knows which Community line is theirs — so it gets an Edit button.
 *
 * <p>While open it asks the relay every few seconds whether that has happened
 * ({@link CommunityLinkClient#status}) and says so the moment it has. The poll only ever writes a
 * field — it never re-lays the screen — so a Copy press or a hover is never reset under the
 * player's cursor. A code lives fifteen minutes; past that the screen says so and stops asking.</p>
 *
 * <p>Every call carries the player's uuid, so without the network consent the screen only explains
 * that, the way {@link CreditEditScreen} does. Done returns to a freshly laid-out Credits page once
 * linked (the standing has to be asked again), else to the page it came from.</p>
 */
public final class DiscordLinkScreen extends Screen {

    private static final int FORM_W = 240;
    private static final int ROW_H = 20;
    private static final int GAP = 6;
    /** Ticks between two status polls — three seconds; the relay answers from a table, not Discord. */
    private static final int POLL_TICKS = 60;
    /** How long the Copy button reads "Copied!" after a press. */
    private static final long COPIED_MS = 1500;
    private static final int COLOUR_CODE = 0xFFF2C230;
    private static final int COLOUR_HINT = 0xFFA0A0A0;

    private final Screen parent;
    /** A new Credits page for Done-after-linking: the current one has already asked where the player stands. */
    private final Supplier<Screen> freshCredits;
    private final boolean consent;

    private String code = "";
    private String command = "/dtlink";
    private long expiresAtMs;
    private boolean requested;
    private boolean linked;
    private boolean expired;
    private boolean pollInFlight;
    private int ticksUntilPoll = POLL_TICKS;
    private long copiedUntilMs;
    private Component status = Component.empty();
    private List<FormattedCharSequence> hintLines = List.of();
    private Button copyButton;
    private Button discordButton;

    public DiscordLinkScreen(Screen parent, Supplier<Screen> freshCredits) {
        super(Component.translatable("gui.dungeontrain.credits.link.title"));
        this.parent = parent;
        this.freshCredits = freshCredits;
        this.consent = RelayChatClient.canConnect();
        OfficialLinks.ensureFetched();
    }

    @Override
    protected void init() {
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        int y = buttonsTop();

        if (consent) {
            int half = (formW - GAP) / 2;
            copyButton = addRenderableWidget(new DarkTintedButton(left, y, half, ROW_H,
                Component.translatable("gui.dungeontrain.credits.link.copy"), b -> copyCode()));
            discordButton = addRenderableWidget(new DarkTintedButton(left + half + GAP, y, formW - half - GAP, ROW_H,
                Component.translatable("gui.dungeontrain.credits.link.open_discord"), b -> openDiscord()));
            y += ROW_H + GAP;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> done())
            .bounds(left, y, formW, ROW_H).build());

        Component hint = consent
            ? Component.translatable("gui.dungeontrain.credits.link.instructions")
            : Component.translatable("gui.dungeontrain.credits.link.no_consent");
        hintLines = font.split(FormattedText.of(hint.getString()), formW);
        updateButtons();

        // Once per screen, not per re-layout: a resize must not spend another code.
        if (consent && !requested) {
            requested = true;
            status = Component.translatable("gui.dungeontrain.credits.link.requesting").withStyle(ChatFormatting.GRAY);
            CommunityLinkClient.start().whenComplete((start, err) -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) mc.execute(() -> onStarted(err != null ? null : start));
            });
        }
    }

    /** Where the button rows begin: below the title, the instructions and the big code. */
    private int buttonsTop() {
        return this.height / 2 + 6;
    }

    private void onStarted(CommunityLinkClient.Start start) {
        if (Minecraft.getInstance().screen != this) return;
        if (start != null && start.ok()) {
            code = start.code();
            expiresAtMs = System.currentTimeMillis() + Math.max(60, start.expiresInSec()) * 1000L;
            ticksUntilPoll = POLL_TICKS;
            status = Component.translatable("gui.dungeontrain.credits.link.waiting").withStyle(ChatFormatting.GRAY);
        } else {
            String key = start == null ? "failed" : switch (start.error()) {
                case NO_CONSENT -> "no_consent";
                case RATE_LIMITED -> "rate_limited";
                case UNSUPPORTED, DISABLED -> "unsupported";
                default -> "failed";
            };
            status = Component.translatable("gui.dungeontrain.credits.link." + key).withStyle(ChatFormatting.RED);
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean haveCode = !code.isEmpty() && !linked && !expired;
        if (copyButton != null) copyButton.active = haveCode;
        if (discordButton != null) discordButton.active = haveCode;
    }

    @Override
    public void tick() {
        super.tick();
        if (code.isEmpty() || linked || expired) return;
        if (System.currentTimeMillis() > expiresAtMs) {
            expired = true;
            status = Component.translatable("gui.dungeontrain.credits.link.expired").withStyle(ChatFormatting.GOLD);
            updateButtons();
            return;
        }
        if (--ticksUntilPoll > 0 || pollInFlight) return;
        ticksUntilPoll = POLL_TICKS;
        pollInFlight = true;
        CommunityLinkClient.status().whenComplete((s, err) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                pollInFlight = false;
                if (mc.screen != this || s == null || !s.ok() || !s.linked()) return;
                linked = true;
                status = Component.translatable("gui.dungeontrain.credits.link.linked").withStyle(ChatFormatting.GREEN);
                updateButtons();
            });
        });
    }

    private void copyCode() {
        if (code.isEmpty()) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(command + " " + code);
        copiedUntilMs = System.currentTimeMillis() + COPIED_MS;
    }

    /** The vanilla confirm, then back here — the code is still on screen for the paste. */
    private void openDiscord() {
        String url = OfficialLinks.discord();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            Minecraft.getInstance().setScreen(this);
        }, url, true));
    }

    private void done() {
        Minecraft.getInstance().setScreen(linked && freshCredits != null ? freshCredits.get() : parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int formW = Math.min(FORM_W, this.width - 40);
        int left = (this.width - formW) / 2;
        int centreX = this.width / 2;

        // Title, then the instructions, then the command with the code drawn at twice the size —
        // it is read off this screen and typed into another window.
        int y = buttonsTop() - 16 - hintLines.size() * font.lineHeight - 4 - 2 * font.lineHeight - 8;
        g.drawCenteredString(font, title, centreX, y, 0xFFFFFFFF);
        y += 16;
        for (FormattedCharSequence line : hintLines) {
            g.drawString(font, line, left, y, COLOUR_HINT, false);
            y += font.lineHeight;
        }
        y += 4;
        if (consent) {
            String shown = code.isEmpty() ? command + " ......" : command + " " + code;
            g.pose().pushPose();
            g.pose().translate(centreX, y, 0);
            g.pose().scale(2f, 2f, 1f);
            g.drawCenteredString(font, shown, 0, 0, code.isEmpty() ? COLOUR_HINT : COLOUR_CODE);
            g.pose().popPose();
        }

        if (copyButton != null && copyButton.active && System.currentTimeMillis() < copiedUntilMs) {
            copyButton.setMessage(Component.translatable("gui.dungeontrain.credits.link.copied"));
        } else if (copyButton != null) {
            copyButton.setMessage(Component.translatable("gui.dungeontrain.credits.link.copy"));
        }

        int sy = buttonsTop() + (consent ? 2 : 1) * (ROW_H + GAP) + 2;
        if (!status.getString().isEmpty()) {
            for (FormattedCharSequence line : font.split(status, formW)) {
                g.drawString(font, line, left, sy, 0xFFFFFFFF, false);
                sy += font.lineHeight;
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
