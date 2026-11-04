package games.brennan.dungeontrain.portal.home;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalRoomAuthorHeads;
import games.brennan.dungeontrain.train.HeadProfiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Every player head in a home room wears its owner's face: the one in their saved room, the one on
 * the train, the one the test rig stands. Unlike an author room ({@link PortalRoomAuthorHeads}) an
 * already-titled head is re-dressed too, so a head placed with someone else's face in the editor
 * still turns into the owner's when the room is theirs.
 *
 * <p>The face comes from the owner's live session textures while they are online (the integrated
 * server's own player included), else from a profile carrying only their name and uuid, which the
 * skull block entity resolves itself through the server's session service — so an offline owner's
 * head still fills in once the lookup answers, and stays a plain head without one.</p>
 */
public final class HomeRoomHeads {

    private static final Logger LOGGER = LogUtils.getLogger();

    private HomeRoomHeads() {}

    /** Dress every player head inside the room box as {@code owner} (dashless uuid). Returns how many. */
    public static int dress(ServerLevel level, BlockPos origin, Vec3i size, String owner, String ownerName) {
        if (level == null || origin == null || size == null) return 0;
        Optional<ResolvableProfile> profile = profileFor(level, owner, ownerName);
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
                    skull.setOwner(profile.get());
                    skull.setChanged();
                    level.sendBlockUpdated(cursor.immutable(), state, state, 3);
                    dressed++;
                }
            }
        }
        if (dressed > 0) {
            LOGGER.info("[DungeonTrain] home room: dressed {} head(s) as its owner '{}'", dressed, ownerName);
        }
        return dressed;
    }

    /** The owner's face: their live textures when online, else a name+uuid profile the skull resolves itself. */
    static Optional<ResolvableProfile> profileFor(ServerLevel level, String owner, @Nullable String ownerName) {
        UUID uuid = parseDashless(owner);
        if (uuid == null) return Optional.empty();
        ServerPlayer online = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(uuid);
        String name = online != null ? online.getGameProfile().getName() : ownerName;
        if (online != null) {
            Property textures = online.getGameProfile().getProperties().get("textures").stream().findFirst().orElse(null);
            Optional<ResolvableProfile> live = textures == null
                ? Optional.empty()
                : PortalRoomAuthorHeads.fromTextures(textures.value()).flatMap(s -> HeadProfiles.of(s.url(), s.slim(), name));
            if (live.isPresent()) return live;
        }
        return Optional.of(lazy(uuid, name));
    }

    /** A profile with no textures yet: the skull block entity fetches them by name/uuid when it loads. */
    static ResolvableProfile lazy(UUID uuid, @Nullable String name) {
        Optional<String> title = HeadProfiles.isPlayerName(name) ? Optional.of(name) : Optional.empty();
        return new ResolvableProfile(title, Optional.of(uuid), new PropertyMap());
    }

    /** {@code "0123…"} (32 hex) or a dashed uuid → UUID; null for anything else. */
    @Nullable
    static UUID parseDashless(@Nullable String owner) {
        if (owner == null) return null;
        String s = owner.trim();
        try {
            if (s.length() == 32) {
                String dashed = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                    + s.substring(16, 20) + "-" + s.substring(20);
                return UUID.fromString(dashed);
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The owner's name for a room that carries only a uuid: the server's profile cache, else blank. */
    public static String nameFor(ServerLevel level, String owner) {
        UUID uuid = parseDashless(owner);
        if (uuid == null || level.getServer() == null) return "";
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        var cache = level.getServer().getProfileCache();
        if (cache == null) return "";
        return cache.get(uuid).map(GameProfile::getName).orElse("");
    }
}
