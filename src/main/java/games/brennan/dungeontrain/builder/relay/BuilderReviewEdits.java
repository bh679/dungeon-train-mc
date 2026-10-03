package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * The developer's verdict on somebody else's build, from the editor — Accept, Feedback or Decline, with
 * a comment the build's owner reads in My Builds (and in their inbox, when they have a chat thread).
 *
 * <p>Only the developer may: a dev build of the mod holding the relay admin URL ({@link
 * BuilderNoteEdits#isDeveloper}), and never on their own build — a verdict on your own work is not a
 * review, and the relay would accept it, so the refusal has to live here. The relay checks the admin
 * cap again on its side; this class decides nothing the relay does not re-check.</p>
 *
 * <p>Sibling of {@link BuilderNoteEdits}, which edits the <em>author's</em> answers; this writes the
 * <em>reviewer's</em>. Kept apart because the two are allowed to different people.</p>
 */
public final class BuilderReviewEdits {

    /** Longest comment sent — the relay clips at the same length. */
    public static final int COMMENT_MAX = 1000;

    private BuilderReviewEdits() {}

    /** Whether {@code player} may review a build owned by {@code ownerUuid}: the developer, not the owner. */
    public static boolean canReview(ServerPlayer player, String ownerUuid) {
        if (player == null || !BuilderNoteEdits.isDeveloper()) return false;
        return !BuilderNoteEdits.sameOwner(player.getUUID().toString(), ownerUuid);
    }

    /** True for a verdict the editor's buttons can set — never {@code none} or {@code submitted}. */
    public static boolean isVerdict(String review) {
        return BuilderReviewState.ACCEPTED.equals(review) || BuilderReviewState.FEEDBACK.equals(review)
                || BuilderReviewState.DECLINED.equals(review);
    }

    /** How a review went: whether the relay took it, and the chat line that says so. */
    public record Outcome(boolean ok, Component message) {}

    /**
     * Record {@code review} + {@code comment} on the build. Resolves to whether it took and the chat
     * line saying how it went; the caller decides what else follows a success (the Discord post on an
     * accept).
     */
    public static CompletableFuture<Outcome> review(ServerPlayer player, int relayId, String ownerUuid,
                                                    boolean live, String review, String comment) {
        if (!isVerdict(review) || !canReview(player, ownerUuid)) {
            return CompletableFuture.completedFuture(new Outcome(false,
                    Component.translatable("gui.dungeontrain.builder.profile.review.not_allowed").withStyle(ChatFormatting.YELLOW)));
        }
        String by = player.getGameProfile().getName();
        return SharedCarriageClient.adminSetReview(relayId, live, review, clean(comment), by)
                .thenApply(status -> new Outcome(status == SharedCarriageClient.CallStatus.OK, said(status)));
    }

    /** The comment as sent: trimmed, CRLF folded, clipped. Package-private for tests. */
    static String clean(String comment) {
        if (comment == null) return "";
        String c = comment.replace("\r\n", "\n").replace('\r', '\n').strip();
        return c.length() <= COMMENT_MAX ? c : c.substring(0, COMMENT_MAX);
    }

    /** The chat line for a review write's outcome. */
    static Component said(SharedCarriageClient.CallStatus status) {
        return switch (status) {
            case OK -> Component.translatable("gui.dungeontrain.builder.profile.review.saved").withStyle(ChatFormatting.GREEN);
            case UNKNOWN -> Component.translatable("gui.dungeontrain.builder.profile.gone_short").withStyle(ChatFormatting.YELLOW);
            case FORBIDDEN -> Component.translatable("gui.dungeontrain.builder.profile.review.not_allowed").withStyle(ChatFormatting.YELLOW);
            // A 400 from a relay that predates 'feedback' arrives as ERROR too: "didn't save" is right.
            case ERROR, TIMEOUT -> Component.translatable("gui.dungeontrain.builder.profile.action_failed").withStyle(ChatFormatting.RED);
        };
    }
}
