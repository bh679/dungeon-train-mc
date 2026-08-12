package games.brennan.dungeontrain.client.portal;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The render model for a puppet of a <b>player</b>.
 *
 * <p>{@link RemotePlayer} is the client's own class for "some other player", which is exactly what a
 * puppet has to look like — right skin, right arm width, right nameplate. It is used here purely as
 * something to draw: this entity is never added to the client level, never ticked, and never
 * interacted with. {@code PortalPuppetRenderer} positions it and hands it to the entity renderer.</p>
 *
 * <p><b>Why not just reuse the source player's UUID.</b> The skin would then resolve for free, but the
 * client's entity storage keys entities by UUID, and a second entity claiming a live player's id is
 * asking for the two to be confused by anything that looks one up. The puppet gets a UUID derived
 * from the source's instead, and the one thing that genuinely needed the original — the skin — asks
 * for it explicitly below.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PortalPuppetPlayer extends RemotePlayer {

    /**
     * Every optional skin layer switched on — hat, jacket, sleeves, trouser legs.
     *
     * <p>A player's real value arrives from the server in their entity data; a puppet has none, and
     * the default of zero would strip the outer layer off the model. Many skins put most of their
     * detail there, so the puppet would not merely look plain, it would look like a different person
     * than the one standing in the other corridor.</p>
     */
    private static final byte ALL_SKIN_LAYERS = 0x7F;

    /** Distinguishes this puppet's derived UUID from any other derivation of the same id. */
    private static final String NAMESPACE = "dungeontrain:portal-puppet:";

    private final UUID sourceId;

    public PortalPuppetPlayer(ClientLevel level, UUID sourceId, String name) {
        super(level, new GameProfile(derivedId(sourceId), name));
        this.sourceId = sourceId;

        // Belt and braces: nothing ticks this entity, so neither flag should ever be consulted. They
        // cost nothing and mean a stray tick from somewhere could not walk the puppet off anywhere.
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setSilent(true);

        this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_SKIN_LAYERS);
    }

    /** Stable, collision-free stand-in id for a source player. */
    private static UUID derivedId(UUID sourceId) {
        return UUID.nameUUIDFromBytes((NAMESPACE + sourceId).getBytes(StandardCharsets.UTF_8));
    }

    /** The player this puppet stands for. */
    public UUID sourceId() {
        return sourceId;
    }

    /**
     * The <b>source</b> player's skin, not this puppet's.
     *
     * <p>{@code AbstractClientPlayer} resolves a skin by looking the entity's own UUID up in the tab
     * list, which for a derived id finds nothing and falls back to a default Steve or Alex. Asking
     * for the source's entry instead gets the real skin, and with it the slim/wide model — which also
     * decides which of the two player renderers draws this puppet.</p>
     *
     * <p>The fallback still matters: a player can be in a corridor before their profile has arrived,
     * or after they have left. A default skin is the right answer then, not a crash and not a
     * missing puppet.</p>
     */
    @Override
    public PlayerSkin getSkin() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(sourceId);
        return info == null ? DefaultPlayerSkin.get(sourceId) : info.getSkin();
    }
}
