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
 * <p>The profile carries the wearer's NAME as well as their face, because vanilla titles the item
 * from it: {@code PlayerHeadItem.getName} renders {@code block.minecraft.player_head.named}
 * ("%s's Head") whenever the profile has a name, and the default "Player Head" when it does not. So
 * a mined head reads "Ranboo's Head" with no naming code of our own — and the name rides the same
 * {@code minecraft:profile} component the skin does, which is why it survives being mined.</p>
 *
 * <p>The uuid is still derived from the texture URL rather than being the wearer's real one: the
 * client needs a stable id per distinct skin and nothing more, and a real uuid is a join key to the
 * rest of a player's record.</p>
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

    /**
     * Minecraft's username charset. Vanilla's {@code ExtraCodecs.PLAYER_NAME} rejects anything else,
     * and a rejected name would fail the whole profile encode — so a name that is not a plain
     * username is dropped and the head simply stays untitled.
     */
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private HeadProfiles() {}

    /** True when {@code url} is a Mojang CDN skin texture URL this class will build a profile from. */
    public static boolean isTextureUrl(String url) {
        return url != null && TEXTURE_URL.matcher(url).matches();
    }

    /** True when {@code name} is a plain Minecraft username, and so safe to title a head with. */
    public static boolean isPlayerName(String name) {
        return name != null && PLAYER_NAME.matcher(name).matches();
    }

    /**
     * A head profile wearing the skin at {@code textureUrl} and titled {@code name}, or empty when
     * the URL is not one this class accepts. {@code slim} picks the Alex-style 3-pixel arm model —
     * which a head never shows, but the metadata is part of the texture blob and is carried
     * faithfully so the same value can dress a mob.
     *
     * <p>A blank or non-username {@code name} yields an untitled head ("Player Head") rather than no
     * head at all: the face is the feature, and the title is the part that may be missing — for a
     * death recorded without a name, or against a relay too old to send one.</p>
     */
    public static Optional<ResolvableProfile> of(String textureUrl, boolean slim, String name) {
        if (!isTextureUrl(textureUrl)) return Optional.empty();
        PropertyMap properties = new PropertyMap();
        properties.put("textures", new Property("textures", textureValue(textureUrl, slim)));
        Optional<String> title = isPlayerName(name) ? Optional.of(name) : Optional.empty();
        return Optional.of(new ResolvableProfile(title, Optional.of(stableId(textureUrl)), properties));
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
