package games.brennan.dungeontrain.client.progress;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.chat.RelayChatClient;
import games.brennan.dungeontrain.progress.ProgressAccountFile;
import games.brennan.dungeontrain.progress.ProgressClient;
import games.brennan.dungeontrain.progress.ProgressCredential;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side owner of this install's relay progress account: obtains a credential once, caches it in
 * {@link ProgressAccountFile}, and hands it to whoever needs it.
 *
 * <p>The credential is obtained by trying, in order:</p>
 *
 * <ol>
 *   <li><b>The stored credential</b> — the normal path after the first launch.</li>
 *   <li><b>Mojang verification</b> — the relay issues a {@code serverId} nonce, this client calls
 *       {@link MinecraftSessionService#joinServer} exactly as vanilla does when connecting to an
 *       online-mode server, and the relay confirms it with Mojang's {@code hasJoined}. That proves the
 *       player really owns the account, with no API key and no new dependency, and puts them in the
 *       {@code mojang:<uuid>} namespace.</li>
 *   <li><b>A minted token</b> — for an offline/cracked profile, a Mojang outage, or any failure above.
 *       These land in the separate {@code token:} namespace precisely so they can never occupy the
 *       identity of a premium player who has not signed up yet (an offline uuid is only
 *       {@code MD5("OfflinePlayer:" + name)}, so every "Steve" shares one).</li>
 * </ol>
 *
 * <p>Everything here is off the render thread and no-throw: a player whose credential can't be obtained
 * simply plays with local-only progress, exactly as before this feature existed. Nothing blocks login.</p>
 */
public final class ProgressAccountClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Guards against two logins racing to mint two accounts for the same install. */
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private ProgressAccountClient() {}

    /**
     * The credential for this install, obtaining one if needed. Resolves to {@code null} when the
     * player has not granted network consent, or when the relay could not be reached at all.
     */
    public static CompletableFuture<ProgressCredential> ensure() {
        ProgressCredential stored = ProgressAccountFile.load();
        if (stored != null && stored.isUsable()) return CompletableFuture.completedFuture(stored);

        // Progress sync rides the same consent gate as every other relay call — no consent, no upload.
        if (!RelayChatClient.canConnect()) return CompletableFuture.completedFuture(null);
        if (!IN_FLIGHT.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);

        return claim().whenComplete((credential, err) -> {
            IN_FLIGHT.set(false);
            if (credential != null) ProgressAccountFile.save(credential);
        });
    }

    /** The stored credential without attempting to obtain one. Null until {@link #ensure} succeeds. */
    public static ProgressCredential current() {
        return ProgressAccountFile.load();
    }

    /**
     * Adopt a credential the player recovered on this machine (the recovery-code paste). Replaces any
     * stored account.
     */
    public static void adopt(ProgressCredential credential) {
        if (credential != null && credential.isUsable()) ProgressAccountFile.save(credential);
    }

    /** Verify with Mojang if we can; mint a token account if we can't. Never throws. */
    private static CompletableFuture<ProgressCredential> claim() {
        return verifyWithMojang()
                .thenCompose(verified -> {
                    if (verified != null) {
                        LOGGER.info("[DungeonTrain] progress account verified against Mojang.");
                        return CompletableFuture.completedFuture(verified);
                    }
                    User user = user();
                    LOGGER.info("[DungeonTrain] progress account could not be verified with Mojang — "
                            + "minting a local account instead (offline profiles are fully supported).");
                    return ProgressClient.mintToken(
                            user == null ? null : user.getProfileId(),
                            user == null ? null : user.getName());
                })
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] progress account claim failed — {}", t.toString());
                    return null;
                });
    }

    /**
     * Run the join-server handshake. Resolves to {@code null} on ANY failure — an offline profile, an
     * expired session, Mojang being down, or the relay refusing. That is not an error state: it is the
     * documented route to the token namespace.
     */
    private static CompletableFuture<ProgressCredential> verifyWithMojang() {
        User user = user();
        if (user == null || user.getName() == null) return CompletableFuture.completedFuture(null);
        UUID profileId = user.getProfileId();
        String accessToken = user.getAccessToken();
        if (profileId == null || accessToken == null || accessToken.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return ProgressClient.challenge().thenCompose(serverId -> {
            if (serverId == null) return CompletableFuture.completedFuture(null);
            // joinServer is a blocking network call to Mojang — keep it off the caller's thread.
            return CompletableFuture.supplyAsync(() -> {
                try {
                    MinecraftSessionService session = Minecraft.getInstance().getMinecraftSessionService();
                    if (session == null) return null;
                    session.joinServer(profileId, accessToken, serverId);
                    return serverId;
                } catch (Throwable t) {
                    // AuthenticationException for an offline/unauthenticated profile — expected.
                    LOGGER.debug("[DungeonTrain] progress: Mojang joinServer declined — {}", t.toString());
                    return null;
                }
            }).thenCompose(joined -> joined == null
                    ? CompletableFuture.completedFuture(null)
                    : ProgressClient.verify(joined, user.getName()));
        });
    }

    private static User user() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getUser();
    }
}
