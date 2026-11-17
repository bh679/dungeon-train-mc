package games.brennan.dungeontrain.client.support;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Skin faces for the "on behalf of" picker. The player's own skin is already loaded; anyone else's
 * comes from the tab list when they share the server, otherwise from Mojang's name lookup
 * (see {@link #request}) (async: the default face shows until it lands, and stays for a name with no account).
 * Like {@code live/deathpages/WatcherFaces}, keyed by name instead of uuid.
 */
final class RecipientFaces {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Map<String, PlayerSkin> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REQUESTED = new ConcurrentHashMap<>();
    /**
     * The default face, shown while a name loads or when it has no account: Steve. {@code
     * DefaultPlayerSkin.get} picks from its eighteen defaults by uuid hash; index 15 is wide Steve.
     */
    private static final PlayerSkin DEFAULT = DefaultPlayerSkin.get(new java.util.UUID(0L, 15L));

    private RecipientFaces() {}

    /** The player's own skin. */
    static PlayerSkin self() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) return mc.player.getSkin();
        return mc.getSkinManager().getInsecureSkin(mc.getGameProfile());
    }

    /**
     * {@code name}'s skin, or the default face while it loads or when {@code lookup} is false (the
     * name is still being typed: no request per keystroke).
     */
    static PlayerSkin of(String name, boolean lookup) {
        if (!DonationRecipient.isValidName(name)) return DEFAULT;
        String key = name.toLowerCase(Locale.ROOT);
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        PlayerInfo info = conn == null ? null : conn.getPlayerInfo(name);
        if (info != null) return info.getSkin();
        PlayerSkin loaded = LOADED.get(key);
        if (loaded != null) return loaded;
        if (lookup) request(name, key);
        return DEFAULT;
    }

    /**
     * Resolve {@code name} to a profile and load its skin, off the render thread. Player heads'
     * {@code SkullBlockEntity.fetchGameProfile} only works while a world is loaded (its profile cache
     * is set up by the server), and this picker also opens on the title screen and in multiplayer, so
     * the lookup goes the way the server's own does: Mojang's name lookup, then the session service
     * for the textures, then the skin manager.
     */
    private static void request(String name, String key) {
        if (REQUESTED.putIfAbsent(key, Boolean.TRUE) != null) return;
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> lookupProfile(mc, name), Util.backgroundExecutor())
                .thenCompose(profile -> profile == null
                        ? CompletableFuture.<PlayerSkin>completedFuture(null)
                        : mc.getSkinManager().getOrLoad(profile))
                .whenComplete((skin, err) -> {
                    LOGGER.debug("[DungeonTrain] recipient face {}: {}", name,
                            skin != null ? skin.texture() : err != null ? err.toString() : "no account");
                    if (skin != null) LOADED.put(key, skin);
                    // An error may be the network: forget it so a later look tries again. "No account"
                    // is an answer, and asking again every frame would only repeat it.
                    else if (err != null) REQUESTED.remove(key);
                });
    }

    /** {@code name}'s full profile (textures included), or null when no account has that name. */
    @Nullable
    private static GameProfile lookupProfile(Minecraft mc, String name) {
        AtomicReference<GameProfile> found = new AtomicReference<>();
        new YggdrasilAuthenticationService(mc.getProxy()).createProfileRepository()
                .findProfilesByNames(new String[]{name}, new ProfileLookupCallback() {
                    @Override
                    public void onProfileLookupSucceeded(GameProfile profile) {
                        found.set(profile);
                    }

                    @Override
                    public void onProfileLookupFailed(String profileName, Exception e) {
                        // Unknown name or the lookup failed: the default face stays.
                    }
                });
        GameProfile bare = found.get();
        if (bare == null) return null;
        ProfileResult full = mc.getMinecraftSessionService().fetchProfile(bare.getId(), false);
        return full != null ? full.profile() : bare;
    }
}
