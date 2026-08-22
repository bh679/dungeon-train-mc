package games.brennan.dungeontrain.train;

import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the {@link ResolvableProfile} that makes a {@code player_head} wear a given Mojang skin.
 *
 * <p>A head's skin lives in its owner profile's {@code textures} property — the same base64 JSON blob
 * the session server hands out, which the client's skin manager unpacks into a texture. Writing it
 * onto the block entity (rather than tinting the render) is what makes the skin survive mining: the
 * vanilla {@code blocks/player_head} loot table copies the {@code minecraft:profile} component off the
 * block entity onto the dropped item, and placing that item writes it back.</p>
 *
 * <p><b>Why a texture URL and never a username:</b> a profile carrying properties is already
 * {@linkplain ResolvableProfile#isResolved() resolved}, so the skull never queries Mojang for it.
 * A name-only profile would instead resolve per head, per world, against a rate-limited session
 * server, and would fail outright in an offline-mode world. {@code playermob}'s skin registry made
 * the same call — its entries are pre-resolved {@code textures.minecraft.net} URLs — which is why the
 * relay's dead-player skins and the PlayerMob fallback can share one code path.</p>
 *
 * <p>The profile is deliberately <em>anonymous</em>: no name, and a uuid derived from the texture URL
 * rather than the dead player's own. Nothing here identifies whose skin it is.</p>
 */
public final class HeadProfiles {

    /**
     * The only URLs a head will wear. Mojang's CDN is the sole host the client's authlib will load a
     * skin from anyway, so anything else is a bad relay response, not a skin — rejected at the
     * boundary rather than silently producing an invisible head.
     */
    private static final Pattern TEXTURE_URL =
        Pattern.compile("^https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{16,128}$");

    /** Namespace for {@link #stableId} — keeps derived head uuids out of any real player's space. */
    private static final String ID_NAMESPACE = "dungeontrain:head:";

    private HeadProfiles() {}

    /** True when {@code url} is a Mojang CDN skin texture URL this class will build a profile from. */
    public static boolean isTextureUrl(String url) {
        return url != null && TEXTURE_URL.matcher(url).matches();
    }

    /**
     * A head profile wearing the skin at {@code textureUrl}, or empty when the URL is not one this
     * class accepts. {@code slim} picks the Alex-style 3-pixel arm model — which a head never shows,
     * but the metadata is part of the texture blob and is carried faithfully so the same value can
     * dress a mob.
     */
    public static Optional<ResolvableProfile> of(String textureUrl, boolean slim) {
        if (!isTextureUrl(textureUrl)) return Optional.empty();
        PropertyMap properties = new PropertyMap();
        properties.put("textures", new Property("textures", textureValue(textureUrl, slim)));
        // No name: an anonymous profile, and one fewer field for ExtraCodecs.PLAYER_NAME to reject.
        return Optional.of(new ResolvableProfile(Optional.empty(), Optional.of(stableId(textureUrl)), properties));
    }

    /**
     * The base64 {@code textures} property value for {@code url} — the minimal form of Mojang's own
     * payload: just the SKIN entry, plus the model metadata when (and only when) the skin is slim,
     * exactly as the session server omits it for a wide skin.
     */
    static String textureValue(String url, boolean slim) {
        String meta = slim ? ",\"metadata\":{\"model\":\"slim\"}" : "";
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"" + meta + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A uuid derived from the texture URL. Two heads wearing the same skin share a uuid and two
     * wearing different skins do not, which is all the client needs; deriving it from the URL rather
     * than reusing the dead player's uuid keeps the head from carrying their identity.
     */
    static UUID stableId(String url) {
        return UUID.nameUUIDFromBytes((ID_NAMESPACE + url).getBytes(StandardCharsets.UTF_8));
    }
}
