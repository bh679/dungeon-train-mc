package games.brennan.dungeontrain.client.support;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin faces for the "on behalf of" picker. The player's own skin is already loaded; anyone else's
 * comes from the tab list when they share the server, otherwise from the same name lookup player
 * heads use (async: the default face shows until it lands, and stays for a name with no account).
 * Same approach as {@code live/deathpages/WatcherFaces}, keyed by name instead of uuid.
 */
final class RecipientFaces {

    private static final Map<String, PlayerSkin> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REQUESTED = new ConcurrentHashMap<>();
    /** The default face (Steve), shown while a name loads or when it has no account. */
    private static final PlayerSkin DEFAULT = DefaultPlayerSkin.get(new java.util.UUID(0L, 0L));

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

    private static void request(String name, String key) {
        if (REQUESTED.putIfAbsent(key, Boolean.TRUE) != null) return;
        try {
            SkullBlockEntity.fetchGameProfile(name).thenCompose(p -> {
                GameProfile profile = p.orElse(null);
                if (profile == null) return CompletableFuture.<PlayerSkin>completedFuture(null);
                return Minecraft.getInstance().getSkinManager().getOrLoad(profile);
            }).thenAccept(skin -> {
                if (skin != null) LOADED.put(key, skin);
            });
        } catch (RuntimeException e) {
            // No profile service (offline dev client): the default face stays.
        }
    }
}
