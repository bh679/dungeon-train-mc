package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.TemplateLootPrefabs;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.editor.workbench.WorkbenchSidecarFold;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.CallStatus;
import games.brennan.dungeontrain.net.relay.SuggestionClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Send the developer's Workbench edits of somebody else's build back to its author, as a
 * <em>suggested edit</em> the author accepts into their build or turns down from My Builds.
 *
 * <p>The edit never overwrites the author's blocks: the relay holds it beside them
 * ({@link SuggestionClient}) and only the owner's secret promotes it. What goes up is what
 * {@link WorkbenchCommit} would commit — the plot as it stands (saved first), the staged sidecars with
 * the chests folded in, and the loot prefabs it carried — plus a comment saying what changed.</p>
 *
 * <p>Allowed to the same person a review is ({@link BuilderReviewEdits#canReview}): the developer,
 * never on their own build.</p>
 */
public final class WorkbenchSendEdits {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Why a send is refused before anything goes up; {@link #NONE} when it may go. */
    public enum Refusal { NONE, NOT_DEVELOPER, OWN_BUILD, NO_RELAY_ROW, NO_COMMENT }

    private WorkbenchSendEdits() {}

    /** The send's refusal, decided from plain values. Package-private for tests. */
    static Refusal refusal(boolean developer, String playerUuid, WorkbenchStagedBuild build, String comment) {
        if (!developer) return Refusal.NOT_DEVELOPER;
        if (build.mine() || BuilderNoteEdits.sameOwner(playerUuid, build.ownerUuid())) return Refusal.OWN_BUILD;
        if (build.relayId() <= 0 || build.ownerUuid().isBlank()) return Refusal.NO_RELAY_ROW;
        if (BuilderReviewEdits.clean(comment).isEmpty()) return Refusal.NO_COMMENT;
        return Refusal.NONE;
    }

    /** The chat key for a refusal. */
    static String refusalKey(Refusal why) {
        return switch (why) {
            case NOT_DEVELOPER -> "chat.dungeontrain.workbench.send.not_allowed";
            case OWN_BUILD -> "chat.dungeontrain.workbench.send.own_build";
            case NO_RELAY_ROW -> "chat.dungeontrain.workbench.send.no_row";
            case NO_COMMENT -> "chat.dungeontrain.workbench.send.no_comment";
            case NONE -> "";
        };
    }

    /** The chat line for the relay's answer; {@code owner} names who it went to. */
    static Component said(CallStatus status, String owner) {
        return switch (status) {
            case OK -> Component.translatable("chat.dungeontrain.workbench.send.sent", owner).withStyle(ChatFormatting.GREEN);
            // A row the relay does not have, or a relay that predates suggestions: the route 404s both ways.
            case UNKNOWN -> Component.translatable("chat.dungeontrain.workbench.send.unsupported").withStyle(ChatFormatting.YELLOW);
            case FORBIDDEN -> Component.translatable("chat.dungeontrain.workbench.send.not_allowed").withStyle(ChatFormatting.YELLOW);
            case ERROR, TIMEOUT -> Component.translatable("chat.dungeontrain.workbench.send.failed").withStyle(ChatFormatting.RED);
        };
    }

    /**
     * Save {@code stagedId}'s plot and send it to its owner. On the server thread; resolves to the chat
     * line saying how it went.
     */
    public static CompletableFuture<Component> send(ServerPlayer player, ServerLevel overworld, String stagedId,
                                                    String comment) {
        Optional<WorkbenchStagedBuild> found = WorkbenchStagingStore.find(stagedId);
        if (found.isEmpty()) {
            return done(Component.translatable("chat.dungeontrain.workbench.unknown", stagedId));
        }
        WorkbenchStagedBuild staged = found.get();
        Refusal why = refusal(BuilderNoteEdits.isDeveloper(), player.getUUID().toString(), staged, comment);
        if (why != Refusal.NONE) {
            return done(Component.translatable(refusalKey(why)).withStyle(ChatFormatting.YELLOW));
        }
        String blocks;
        try {
            // What stands in the plot is the edit; a build not standing sends what was last saved.
            WorkbenchEditor.save(overworld, stagedId);
            Optional<CompoundTag> tag = WorkbenchStagingStore.readBlocks(stagedId);
            if (tag.isEmpty()) throw new IllegalStateException("no blocks file");
            blocks = CarriageBlockSnapshot.encode(tag.get());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Workbench send: could not package '{}': {}", stagedId, e.toString());
            return done(Component.translatable("chat.dungeontrain.workbench.send.failed").withStyle(ChatFormatting.RED));
        }
        // Re-read: the save may have changed the footprint's record.
        WorkbenchStagedBuild now = WorkbenchStagingStore.find(stagedId).orElse(staged);
        String sidecars = WorkbenchSidecarFold.fold(stagedId);
        String prefabs = TemplateLootPrefabs.encode(now.lootPrefabs());
        String owner = now.ownerName().isBlank() ? now.ownerUuid() : now.ownerName();
        LOGGER.info("[DungeonTrain] Workbench send: '{}' (relay id={}, {}) to {}", stagedId, now.relayId(),
                now.live() ? "live" : "dev", owner);
        return SuggestionClient.adminSuggestEdit(now.relayId(), now.live(), blocks,
                        now.size().getX(), now.size().getY(), now.size().getZ(), sidecars, prefabs,
                        BuilderReviewEdits.clean(comment), player.getGameProfile().getName())
                .thenApply(status -> said(status, owner));
    }

    private static CompletableFuture<Component> done(Component message) {
        return CompletableFuture.completedFuture(message);
    }
}
