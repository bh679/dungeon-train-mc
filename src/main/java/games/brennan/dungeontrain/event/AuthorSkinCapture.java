package games.brennan.dungeontrain.event;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.AuthorSkinRef;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Reads a player's skin texture URL + arm model off their authenticated {@code GameProfile} so a note
 * echo can wear it ({@link AuthorSkinRef}). Same no-network read PlayerMob's reincarnation does at
 * death: the session service only unpacks the {@code textures} property already on the profile.
 * Empty on an offline/dev profile (no textures) or any failure — the echo then falls back to the
 * author's default skin, exactly as before skins were captured.
 */
public final class AuthorSkinCapture {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SLIM_MODEL = "slim";

    private AuthorSkinCapture() {}

    public static Optional<AuthorSkinRef> capture(ServerPlayer player) {
        try {
            MinecraftProfileTexture skin = player.getServer().getSessionService()
                    .getTextures(player.getGameProfile()).skin();
            if (skin == null || skin.getUrl() == null || skin.getUrl().isBlank()) {
                return Optional.empty();
            }
            boolean slim = SLIM_MODEL.equals(skin.getMetadata("model"));
            return Optional.of(new AuthorSkinRef(skin.getUrl(), slim));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Note: could not capture {}'s skin for their echo: {}",
                    player.getGameProfile().getName(), e.toString());
            return Optional.empty();
        }
    }
}
