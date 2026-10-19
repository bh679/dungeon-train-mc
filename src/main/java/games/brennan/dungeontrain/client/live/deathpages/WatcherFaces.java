package games.brennan.dungeontrain.client.live.deathpages;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin faces for the crowd page's podium. An in-game viewer's face comes from the tab list when they
 * share the server, otherwise from the same profile lookup player heads use (async: the default face
 * shows until it lands). A website viewer whose Discord is linked to a game account comes with that
 * account's uuid and is looked up the same way; any other website viewer gets the default face.
 */
final class WatcherFaces {

    private static final Map<UUID, PlayerSkin> LOADED = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> REQUESTED = new ConcurrentHashMap<>();

    private WatcherFaces() {}

    /** Draw {@code uuid}'s face (null: a viewer with no game account) at {@code size} px, at {@code alpha}. */
    static void draw(GuiGraphics g, @Nullable String uuid, int x, int y, int size, float alpha) {
        PlayerSkin skin = skinOf(parse(uuid));
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        PlayerFaceRenderer.draw(g, skin, x, y, size);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    static PlayerSkin skinOf(@Nullable UUID id) {
        if (id == null) return DefaultPlayerSkin.get(new UUID(0L, 0L));
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        PlayerInfo info = conn == null ? null : conn.getPlayerInfo(id);
        if (info != null) return info.getSkin();
        PlayerSkin loaded = LOADED.get(id);
        if (loaded != null) return loaded;
        request(id);
        return DefaultPlayerSkin.get(id);
    }

    private static void request(UUID id) {
        if (REQUESTED.putIfAbsent(id, Boolean.TRUE) != null) return;
        try {
            SkullBlockEntity.fetchGameProfile(id).thenCompose(p -> {
                GameProfile profile = p.orElse(null);
                if (profile == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
                return Minecraft.getInstance().getSkinManager().getOrLoad(profile);
            }).thenAccept(skin -> {
                if (skin != null) LOADED.put(id, skin);
            });
        } catch (RuntimeException e) {
            // no profile service (offline dev client): the default face stays
        }
    }

    @Nullable
    static UUID parse(@Nullable String hex) {
        if (hex == null) return null;
        String h = hex.replace("-", "");
        if (!h.matches("[0-9a-fA-F]{32}")) return null;
        try {
            return new UUID(Long.parseUnsignedLong(h.substring(0, 16), 16), Long.parseUnsignedLong(h.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
