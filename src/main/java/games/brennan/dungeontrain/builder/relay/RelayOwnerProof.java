package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prove to the relay that a player really is the Minecraft account behind their uuid, so it will
 * hand back a build's owner secret.
 *
 * <p>Owner uuids are public — they are on every My Builds row, in favourites, in creator search — so
 * the relay no longer treats "the uuid matches" as ownership. It serves the secret only with a
 * {@code serverId} this player's own client joined with through Mojang's session server, the same
 * handshake an online-mode server uses to authenticate a joining player (relay side:
 * {@code owner-proof.js}).</p>
 *
 * <p>Only the physical client holds the access token, so a proof is possible only where the player
 * asking IS this client's signed-in account: the host of a single-player (or LAN-hosting) world. A
 * dedicated server, another player on a LAN world, or an offline/dev account gets no proof, and the
 * callers fall back to what the relay serves without one — the blocks, and no secret.</p>
 *
 * <p>A proof is cached per relay and player for a little under the relay's own window, so a burst of
 * My Builds actions costs one Mojang round trip rather than one each.</p>
 */
public final class RelayOwnerProof {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Under the relay's 10-minute proven window, so a cached proof is never one it has forgotten. */
    static final long CACHE_MS = 9L * 60L * 1000L;

    private record Cached(SharedCarriageClient.OwnerProof proof, long expiresAt) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private RelayOwnerProof() {}

    /** Drop a cached proof the relay did not accept. */
    public static void forget(ServerPlayer player, String baseUrl) {
        if (player != null) CACHE.remove(baseUrl + "|" + player.getUUID());
    }

    /**
     * Whether this process can prove {@code player}'s identity at all — the part worth pinning in a
     * test, split from the call that needs a live client.
     */
    static boolean canProve(boolean physicalClient, boolean singleplayerOwner) {
        return physicalClient && singleplayerOwner;
    }

    /**
     * A proof for {@code player} against the relay at {@code baseUrl}, or null when none can be had.
     * Never fails exceptionally: every failure is "no proof", which the caller already handles.
     */
    public static CompletableFuture<SharedCarriageClient.OwnerProof> obtain(ServerPlayer player, String baseUrl) {
        if (player == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = player.getServer();
        boolean owner = server != null && server.isSingleplayerOwner(player.getGameProfile());
        if (!canProve(FMLEnvironment.dist.isClient(), owner)) {
            return CompletableFuture.completedFuture(null);
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        String key = baseUrl + "|" + uuid;
        Cached hit = CACHE.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(hit.proof());
        }
        return SharedCarriageClient.ownerProofChallenge(uuid.toString(), baseUrl)
                .thenApplyAsync(serverId -> {
                    if (serverId == null || serverId.isEmpty()) return null;
                    if (!games.brennan.dungeontrain.client.OwnerProofJoin.join(uuid, serverId)) return null;
                    SharedCarriageClient.OwnerProof proof = new SharedCarriageClient.OwnerProof(name, serverId);
                    CACHE.put(key, new Cached(proof, System.currentTimeMillis() + CACHE_MS));
                    return proof;
                })
                .exceptionally(t -> {
                    LOGGER.info("[DungeonTrain] Relay owner proof: none for {} — {}", name, t.toString());
                    return null;
                });
    }
}
