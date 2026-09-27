package games.brennan.dungeontrain.client.modcheck;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.ModSuggestClient;
import games.brennan.dungeontrain.client.OwnerProofJoin;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Suggest a mod for the whitelist as this client's signed-in account: owner-proof handshake, then
 * {@link ModSuggestClient#suggest}. The same three-step dance My Builds uses to recover an owner
 * secret ({@code RelayOwnerProof}), but from the title screen — there is no {@code ServerPlayer} here,
 * only the client's own {@link User}.
 *
 * <ol>
 *   <li>{@code POST /carriages/owner-proof {uuid}} — the relay hands back a {@code serverId}.</li>
 *   <li>{@link OwnerProofJoin#join} — tell Mojang this account is joining that serverId (the access
 *       token goes to Mojang only, never to the relay).</li>
 *   <li>{@code POST /mods/suggest} — the relay asks Mojang {@code hasJoined} and records the vote.</li>
 * </ol>
 *
 * <p>An offline or dev account can't be vouched for; that resolves to {@link
 * ModSuggestClient.Result#NOT_PROVEN} without calling the suggest route at all.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ModSuggestProof {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ModSuggestProof() {}

    /** Prove, then suggest. Never fails exceptionally. */
    public static CompletableFuture<ModSuggestClient.Result> suggest(String modId, String comment) {
        try {
            User user = Minecraft.getInstance().getUser();
            if (user == null || user.getType() != User.Type.MSA) {
                return CompletableFuture.completedFuture(ModSuggestClient.Result.NOT_PROVEN);
            }
            UUID uuid = user.getProfileId();
            String name = user.getName();
            String base = RelayTarget.dev();
            return SharedCarriageClient.ownerProofChallenge(uuid.toString(), base)
                    .thenApplyAsync(serverId -> serverId != null && !serverId.isEmpty()
                            && OwnerProofJoin.join(uuid, serverId) ? serverId : null)
                    .thenCompose(serverId -> serverId == null
                            ? CompletableFuture.completedFuture(ModSuggestClient.Result.NOT_PROVEN)
                            : ModSuggestClient.suggest(base, uuid.toString(), name, serverId, modId, comment))
                    .exceptionally(t -> {
                        LOGGER.info("[DungeonTrain] mod suggestion for {} failed: {}", modId, t.toString());
                        return ModSuggestClient.Result.FAILED;
                    });
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] mod suggestion for {} could not start: {}", modId, t.toString());
            return CompletableFuture.completedFuture(ModSuggestClient.Result.FAILED);
        }
    }
}
