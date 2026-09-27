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
 * Suggest a mod for the whitelist as this client's player — the name and uuid the client is using,
 * taken as given by the relay (offline and dev accounts can suggest too). When the account is
 * Microsoft-signed-in it also does the owner-proof handshake My Builds uses ({@code RelayOwnerProof}),
 * which lets the relay mark the suggestion verified:
 *
 * <ol>
 *   <li>{@code POST /carriages/owner-proof {uuid}} — the relay hands back a {@code serverId}.</li>
 *   <li>{@link OwnerProofJoin#join} — tell Mojang this account is joining that serverId (the access
 *       token goes to Mojang only, never to the relay).</li>
 *   <li>{@code POST /mods/suggest} — with the serverId when the join worked, without it otherwise.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class ModSuggestProof {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ModSuggestProof() {}

    /** Suggest ({@code kind}: whitelist, modpack or cheat), proving the account when it can. Never fails exceptionally. */
    public static CompletableFuture<ModSuggestClient.Result> suggest(String modId, ModSuggestClient.Kind kind,
                                                                  String comment) {
        try {
            User user = Minecraft.getInstance().getUser();
            if (user == null) return CompletableFuture.completedFuture(ModSuggestClient.Result.FAILED);
            UUID uuid = user.getProfileId();
            String name = user.getName();
            String base = RelayTarget.dev();
            CompletableFuture<String> proof = user.getType() == User.Type.MSA
                ? SharedCarriageClient.ownerProofChallenge(uuid.toString(), base)
                    .thenApplyAsync(serverId -> serverId != null && !serverId.isEmpty()
                        && OwnerProofJoin.join(uuid, serverId) ? serverId : "")
                    .exceptionally(t -> "")
                : CompletableFuture.completedFuture("");
            return proof
                .thenCompose(serverId -> ModSuggestClient.suggest(base, uuid.toString(), name, serverId, modId, kind, comment))
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
