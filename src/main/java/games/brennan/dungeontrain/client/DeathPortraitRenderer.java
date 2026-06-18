package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.player.PlayerMobAppearance;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Renders a 3D portrait of a single PlayerMob — the one befriended or killed
 * this run — on the death-screen DEEDS page.
 *
 * <p>Reconstructs a throwaway client-side {@link PlayerMobEntity} from the
 * {@link PlayerMobAppearance} carried in the death packet, sets its three skin
 * fields, and draws it with vanilla's
 * {@link InventoryScreen#renderEntityInInventoryFollowsMouse} — which reuses
 * playermob's own registered renderer + skin resolution (echo profile, URL, or
 * bundled vanilla index) for free, so we never resolve a skin ourselves.</p>
 *
 * <p>The entity is built lazily and cached; it is never added to a level or
 * ticked. Construction is safe on the client because the death screen does not
 * pause — the integrated server keeps the client {@link Level} alive behind it.
 * State is per-screen: call {@link #clear()} from the owning screen's
 * {@code removed()} so the cached entity can't pin a stale {@link Level}.</p>
 *
 * <p>The hard references to bundled-playermob classes are safe: playermob is
 * always jarJar'd into Dungeon Train (the same guarantee {@code compat.EchoIdentity}
 * relies on).</p>
 */
public final class DeathPortraitRenderer {

    private PlayerMobAppearance current;
    private PlayerMobEntity entity;

    /**
     * Whether a portrait can be drawn this frame — a client level exists and the
     * playermob entity type is registered. The DEEDS layout consults this BEFORE
     * reserving space, so a failure falls back to the full-width grid rather than
     * leaving an empty gap.
     */
    public boolean available() {
        return Minecraft.getInstance().level != null && PlayerMobRegistry.PLAYER_MOB != null;
    }

    /**
     * Draw {@code appearance}'s portrait into the rect {@code (x1,y1)-(x2,y2)},
     * auto-fitted to the rect by the vanilla helper. The mob's head/body track
     * the cursor. No-op when the appearance is null or the entity can't be built.
     */
    public void render(GuiGraphics g, PlayerMobAppearance appearance,
                       int x1, int y1, int x2, int y2, int scale, float mouseX, float mouseY) {
        if (appearance == null) return;
        PlayerMobEntity mob = obtain(appearance);
        if (mob == null) return;
        InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, x1, y1, x2, y2, scale, 0.0625f, mouseX, mouseY, mob);
    }

    /**
     * Lazily build / refresh the cached entity. Rebuilds when the appearance
     * changes or the client {@link Level} instance changes (e.g. "Board anew"
     * swaps levels). Returns null if construction isn't possible this frame.
     */
    private PlayerMobEntity obtain(PlayerMobAppearance appearance) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        EntityType<PlayerMobEntity> type = PlayerMobRegistry.PLAYER_MOB;
        if (type == null) return null;
        if (entity != null && appearance.equals(current) && entity.level() == level) {
            return entity;
        }
        PlayerMobEntity mob = new PlayerMobEntity(type, level);
        mob.setNoAi(true);
        mob.setSkinIndex(appearance.skinIndex());
        mob.setSkinTextureUrl(appearance.skinTextureUrl());
        mob.setSkinSlim(appearance.slim());
        this.entity = mob;
        this.current = appearance;
        return mob;
    }

    /** Drop the cached entity (and its {@link Level} reference). Call from {@code Screen.removed()}. */
    public void clear() {
        entity = null;
        current = null;
    }
}
