package games.brennan.dungeontrain.client.social;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * What this client last heard from the relay about the player's socials ({@link PlayerSocialsRelay}), for the My
 * Socials block on the Account tab of Options → Dungeon Train. The shape of {@code TwitchAccountState}: a cache so
 * the tab draws at once and redraws when an answer lands.
 *
 * <p>Render thread only: every call resolves its HTTP off-thread and hops back with {@code Minecraft#execute} before
 * touching a field or running {@code onChange}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MySocialsState {

    /** Where the block stands: still asking, an answer, or no answer. */
    public enum Phase { LOADING, READY, FAILED }

    private static Phase phase = Phase.LOADING;
    @Nullable
    private static PlayerSocialsRelay.Status status;
    private static boolean busy;
    private static boolean inFlight;
    /** The last thing a change has to say (browser opened, or why it failed), or empty. */
    private static Component message = Component.empty();
    /** Whether {@link #message} is the browser hint, which carries a Refresh button. */
    private static boolean awaitingBrowser;

    private MySocialsState() {}

    public static Phase phase() {
        return phase;
    }

    @Nullable
    public static PlayerSocialsRelay.Status status() {
        return status;
    }

    public static boolean busy() {
        return busy;
    }

    public static Component message() {
        return message;
    }

    public static boolean awaitingBrowser() {
        return awaitingBrowser;
    }

    /** Ask the relay again, then run {@code onChange} on the render thread. One request at a time. */
    public static void refresh(Runnable onChange) {
        if (inFlight) return;
        inFlight = true;
        if (status == null) phase = Phase.LOADING;
        PlayerSocialsRelay.fetch().whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
            inFlight = false;
            if (err == null && s != null) {
                status = s;
                phase = Phase.READY;
                awaitingBrowser = false;
                message = Component.empty();
            } else if (status == null) {
                phase = Phase.FAILED;
            }
            onChange.run();
        }));
    }

    /** Open {@code via}'s sign-in in the browser. */
    public static void signIn(String via, Runnable onChange) {
        begin(PlayerSocialsRelay.start(via), onChange);
    }

    public static void setTag(String platform, boolean on, Runnable onChange) {
        begin(PlayerSocialsRelay.setTag(platform, on), onChange);
    }

    public static void remove(String platform, Runnable onChange) {
        begin(PlayerSocialsRelay.remove(platform), onChange);
    }

    private static void begin(CompletableFuture<PlayerSocialsRelay.Result> call, Runnable onChange) {
        busy = true;
        message = Component.empty();
        awaitingBrowser = false;
        onChange.run();
        call.thenAccept(r -> Minecraft.getInstance().execute(() -> {
            busy = false;
            if (r.url() != null) {
                Util.getPlatform().openUri(URI.create(r.url()));
                message = Component.translatable("gui.dungeontrain.socials.opened");
                awaitingBrowser = true;
            } else if (r.status() != null) {
                status = r.status();
            } else {
                message = failure(r.failure());
            }
            onChange.run();
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
}
