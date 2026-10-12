package games.brennan.dungeontrain.portal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.BookAuthorsClient;
import games.brennan.dungeontrain.train.HeadProfiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Dresses the {@code player_head} blocks in an author room in that author's face.
 *
 * <p>Runs from {@link PortalRoomLibrarian#tick} the moment a room's author is known — which is after
 * the room was stamped, since the relay has not said who has written what by then. A re-stamped tile
 * registers again and is dressed again by the same pass.</p>
 *
 * <p>The face comes from the relay's author directory ({@link BookAuthorsClient.Author#skinUrl}). For
 * the reader's OWN shelf with no relay skin yet, their live profile's texture stands in. With no skin
 * at all the heads are left as they are. A head that already has an owner is never touched: an
 * author who put a specific face in a template meant that face — the same rule the carriage
 * death-skin pass follows.</p>
 */
public final class PortalRoomAuthorHeads {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PortalRoomAuthorHeads() {}

    /** The skin a head should wear: a CDN texture URL and its arm model. */
    record Skin(String url, boolean slim) {}

    /** Dress every unowned player head in the room box. Returns how many were dressed. */
    public static int dress(ServerLevel level, BlockPos origin, Vec3i size,
                            BookAuthorsClient.Author author, ServerPlayer reader) {
        if (level == null || origin == null || size == null || author == null) return 0;
        Optional<ResolvableProfile> profile = skinFor(author, reader)
            .flatMap(skin -> HeadProfiles.of(skin.url(), skin.slim(), author.name()));
        if (profile.isEmpty()) return 0;
        int dressed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < size.getY(); dy++) {
            for (int dz = 0; dz < size.getZ(); dz++) {
                for (int dx = 0; dx < size.getX(); dx++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.is(Blocks.PLAYER_HEAD) && !state.is(Blocks.PLAYER_WALL_HEAD)) continue;
                    if (!(level.getBlockEntity(cursor) instanceof SkullBlockEntity skull)) continue;
                    if (skull.getOwnerProfile() != null) continue; // authored face wins
                    skull.setOwner(profile.get());
                    skull.setChanged();
                    dressed++;
                }
            }
        }
        if (dressed > 0) {
            LOGGER.info("[DungeonTrain] Author room dressed {} head(s) as '{}'", dressed, author.name());
        }
        return dressed;
    }

    /** The relay's skin for {@code author}, else — for the reader's own shelf — their live one. */
    static Optional<Skin> skinFor(BookAuthorsClient.Author author, ServerPlayer reader) {
        if (HeadProfiles.isTextureUrl(author.skinUrl())) {
            return Optional.of(new Skin(author.skinUrl(), author.slim()));
        }
        if (!author.mine() || reader == null) return Optional.empty();
        Property textures = reader.getGameProfile().getProperties().get("textures").stream()
            .findFirst().orElse(null);
        return textures == null ? Optional.empty() : fromTextures(textures.value());
    }

    /**
     * The SKIN entry of a session-server {@code textures} property, or empty for anything else
     * (offline-mode profiles carry none; a malformed one is ignored rather than thrown).
     */
    static Optional<Skin> fromTextures(String base64) {
        if (base64 == null || base64.isBlank()) return Optional.empty();
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return Optional.empty();
            JsonObject textures = root.getAsJsonObject().getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) {
                return Optional.empty();
            }
            JsonObject skin = textures.getAsJsonObject("SKIN");
            String url = skin.has("url") ? skin.get("url").getAsString() : null;
            if (!HeadProfiles.isTextureUrl(url)) return Optional.empty();
            boolean slim = skin.has("metadata") && skin.get("metadata").isJsonObject()
                && skin.getAsJsonObject("metadata").has("model")
                && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
            return Optional.of(new Skin(url, slim));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
