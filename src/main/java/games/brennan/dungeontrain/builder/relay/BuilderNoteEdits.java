package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * Editing a build's Submit for Review answers after the fact — before it is submitted, while it waits,
 * after it is reviewed.
 *
 * <p>Two people may: the build's <b>owner</b>, who writes through the relay with the build's owner
 * secret (this world's saved one, or recovered as a submit would), and <b>the developer</b> — a dev
 * build of the mod holding the relay admin URL — who writes through the admin cap and so can correct
 * anyone's answers. Nobody else, and the relay checks both again: the owner route wants the secret,
 * the admin route the admin cap.</p>
 */
public final class BuilderNoteEdits {

    private BuilderNoteEdits() {}

    /** Whether two uuids name the same player, dashes and case aside — the relay's own rule. */
    public static boolean sameOwner(String a, String b) {
        String x = norm(a);
        return !x.isEmpty() && x.equals(norm(b));
    }

    private static String norm(String uuid) {
        return uuid == null ? "" : uuid.strip().toLowerCase(java.util.Locale.ROOT).replace("-", "");
    }

    /** True when this install can write through the admin cap: a dev build with the admin URL. */
    public static boolean isDeveloper() {
        return DungeonTrain.isDevBuild() && !RelayTarget.adminSearchBase().isEmpty();
    }

    /** Whether {@code player} may edit the answers of a build owned by {@code ownerUuid}. */
    public static boolean canEdit(ServerPlayer player, String ownerUuid) {
        if (player == null) return false;
        return sameOwner(player.getUUID().toString(), ownerUuid) || isDeveloper();
    }

    /**
     * Write {@code note} as the build's answers. The owner path is tried first whenever the player is
     * the owner, so the developer editing their own build still goes through their own secret. Resolves
     * to the chat line saying how it went.
     */
    public static CompletableFuture<Component> edit(ServerPlayer player, ServerLevel level, int relayId,
                                                    String ownerUuid, boolean live, SubmitNote note) {
        SubmitNote cleaned = note == null ? SubmitNote.EMPTY : note.cleaned();
        if (player != null && sameOwner(player.getUUID().toString(), ownerUuid) && !live) {
            return BuilderRelayUpload.secretFor(player, level, relayId).thenCompose(lookup -> {
                if (lookup.verdict() == BuilderRelayUpload.Adoption.GONE) {
                    return done("gui.dungeontrain.builder.profile.gone_short", ChatFormatting.YELLOW);
                }
                if (lookup.secret().isEmpty()) {
                    return done("gui.dungeontrain.builder.profile.not_yours", ChatFormatting.YELLOW);
                }
                return SharedCarriageClient.setNote(relayId, lookup.secret(), cleaned).thenApply(BuilderNoteEdits::said);
            });
        }
        if (isDeveloper()) {
            return SharedCarriageClient.adminSetNote(relayId, live, cleaned).thenApply(BuilderNoteEdits::said);
        }
        return done("gui.dungeontrain.builder.profile.not_yours", ChatFormatting.YELLOW);
    }

    /** The chat line for a note write's outcome. */
    private static Component said(SharedCarriageClient.CallStatus status) {
        return switch (status) {
            case OK -> Component.translatable("gui.dungeontrain.builder.profile.note.saved").withStyle(ChatFormatting.GREEN);
            case UNKNOWN -> Component.translatable("gui.dungeontrain.builder.profile.gone_short").withStyle(ChatFormatting.YELLOW);
            case FORBIDDEN -> Component.translatable("gui.dungeontrain.builder.profile.not_yours").withStyle(ChatFormatting.YELLOW);
            // TIMEOUT is only ever produced by fetchBuild, never by a note write — listed so the
            // switch stays exhaustive, and folded into ERROR because both mean "it didn't save".
            case ERROR, TIMEOUT -> Component.translatable("gui.dungeontrain.builder.profile.action_failed").withStyle(ChatFormatting.RED);
        };
    }

    private static CompletableFuture<Component> done(String key, ChatFormatting colour) {
        return CompletableFuture.completedFuture(Component.translatable(key).withStyle(colour));
    }
}
