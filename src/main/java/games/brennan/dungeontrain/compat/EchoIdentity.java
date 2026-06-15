package games.brennan.dungeontrain.compat;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

/**
 * Identifies "echoes" — PlayerMobs reincarnated from a fallen player by
 * PlayerMob's reincarnation feature (1-in-4 of Dungeon-Train {@code EVENT}
 * spawns; see {@code games.brennan.playermob.player.PlayerReincarnation}) — and
 * in particular whether an echo embodies a <em>given</em> player's own past
 * life.
 *
 * <p>An echo carries its source player's identity in the entity's
 * {@code SkinTextureUrl} as a {@link SourceProfileSkin} profile-ref
 * ({@code playermob-profile:<uuid>;<name>}), which {@link SourceProfileSkin#decode}
 * resolves to the dead player's UUID; a plain PlayerMob uses one of the bundled
 * vanilla skins and decodes to empty. Keying on the decoded source UUID — not
 * the "Echo of X" display name — means a hand-renamed mob is never a false
 * positive.</p>
 *
 * <p>The hard references to bundled-playermob classes are safe: playermob is
 * always present (jarJar'd into DungeonTrain), the same guarantee
 * {@code murderous_intent.json}'s {@code playermob:player_mob} entity predicate
 * relies on.</p>
 */
public final class EchoIdentity {

    private EchoIdentity() {}

    /**
     * The player whose past life {@code entity} reincarnates, or empty when
     * {@code entity} is not an echo (a {@code null} or non-PlayerMob entity, or
     * a plain PlayerMob).
     */
    public static Optional<UUID> sourcePlayer(Entity entity) {
        if (!(entity instanceof PlayerMobEntity mob)) {
            return Optional.empty();
        }
        return SourceProfileSkin.decode(mob.getSkinTextureUrl()).map(SourceProfileSkin.Ref::uuid);
    }

    /**
     * True when {@code entity} is {@code playerUuid}'s <em>own</em> echo — a
     * reincarnation of that same player's past life. Null-safe.
     */
    public static boolean isOwnEcho(Entity entity, UUID playerUuid) {
        return sourcePlayer(entity).map(playerUuid::equals).orElse(false);
    }
}
