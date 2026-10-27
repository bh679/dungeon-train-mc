package games.brennan.dungeontrain.event;

import com.mojang.authlib.properties.PropertyMap;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.dungeontrain.train.HeadProfiles;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * The second trophy a Death Note echo leaves: its author's head, dropped only when the player the
 * curse was aimed at is the one who kills it. A bystander's kill, a fall, or a Love Note echo leaves
 * the book alone.
 *
 * <p>The author's identity is read off the echo's own skin ref ({@link SourceProfileSkin}, baked by
 * {@code DeathNoteEchoSpawner}), so echoes saved before this existed still qualify. That ref carries
 * no texture URL, so the head starts as an unresolved name + uuid profile, already titled
 * "&lt;Author&gt;'s Head", and is swapped for the resolved one once vanilla's skull profile lookup
 * answers. Offline mode never answers: the head keeps its title and wears the default face.</p>
 */
public final class DeathNoteEchoHead {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DeathNoteEchoHead() {}

    /** Who the head belongs to: a username (always) and, when the echo carried one, their uuid. */
    record Identity(String name, Optional<UUID> uuid) {}

    /** True when this death earns a head: a Death Note echo killed by its own target. */
    static boolean shouldDrop(NoteKind kind, UUID killer, String targetUuid) {
        return kind == NoteKind.DEATH && killer != null && killer.toString().equals(targetUuid);
    }

    /**
     * The author behind {@code skinRef}, else a name-only identity from {@code fallbackName}; empty
     * when neither gives a plain username (vanilla's profile codec rejects anything else).
     */
    static Optional<Identity> identityFor(String skinRef, String fallbackName) {
        Optional<SourceProfileSkin.Ref> ref = SourceProfileSkin.decode(skinRef);
        if (ref.isPresent() && HeadProfiles.isPlayerName(ref.get().name())) {
            return Optional.of(new Identity(ref.get().name(), Optional.of(ref.get().uuid())));
        }
        if (HeadProfiles.isPlayerName(fallbackName)) {
            return Optional.of(new Identity(fallbackName, Optional.empty()));
        }
        return Optional.empty();
    }

    /** Drop {@code echo}'s author head where it died, if {@link #shouldDrop} said it earned one. */
    static void drop(PlayerMobEntity echo, String fallbackName) {
        Optional<Identity> identity = identityFor(echo.getSkinTextureUrl(), fallbackName);
        if (identity.isEmpty()) {
            LOGGER.debug("[DungeonTrain] DeathNote: echo has no usable author identity, no head dropped");
            return;
        }
        ResolvableProfile profile = new ResolvableProfile(
                Optional.of(identity.get().name()), identity.get().uuid(), new PropertyMap());
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, profile);
        ItemEntity drop = new ItemEntity(echo.level(), echo.getX(), echo.getY() + 0.5, echo.getZ(), head);
        drop.setDefaultPickUpDelay();
        echo.level().addFreshEntity(drop);
        resolveFace(echo.level().getServer(), drop, head, profile);
    }

    /**
     * Look the author's face up and put it on the head. A head still on the ground is re-set as a
     * copy so clients redraw it; one already picked up is updated in place, and vanilla resolves any
     * still-faceless head again the next time it loads.
     */
    private static void resolveFace(MinecraftServer server, ItemEntity drop, ItemStack head,
                                    ResolvableProfile profile) {
        if (server == null) return;
        try {
            profile.resolve().whenComplete((resolved, error) -> {
                if (error != null || resolved == null || !resolved.isResolved()) {
                    LOGGER.debug("[DungeonTrain] DeathNote: head face lookup failed: {}",
                            error == null ? "no profile" : error.toString());
                    return;
                }
                server.execute(() -> {
                    if (drop.isAlive()) {
                        ItemStack faced = drop.getItem().copy();
                        faced.set(DataComponents.PROFILE, resolved);
                        drop.setItem(faced);
                    } else {
                        head.set(DataComponents.PROFILE, resolved);
                    }
                });
            });
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] DeathNote: head face lookup unavailable: {}", e.toString());
        }
    }
}
