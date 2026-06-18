package games.brennan.dungeontrain.player;

import games.brennan.playermob.entity.PlayerMobEntity;

/**
 * Immutable snapshot of a PlayerMob's visual identity — the three skin fields
 * playermob's renderer needs to reproduce it: the bundled-skin index, an
 * optional skin-texture URL ({@code ""} when none — a Mojang CDN URL or an
 * "echo" {@code playermob-profile:<uuid>;<name>} ref), and the slim-arms flag.
 *
 * <p>Captured server-side at the moment a mob is befriended or killed (the live
 * entity is loaded then — by death-screen time it may be unloaded), carried in
 * {@link games.brennan.dungeontrain.net.DeathStatsPacket}, and replayed onto a
 * throwaway client-side {@code PlayerMobEntity} so the death screen can render
 * the mob's portrait. The bundled {@link PlayerMobEntity}'s registered renderer
 * resolves any of the three skin variants from these fields automatically.</p>
 *
 * <p>The hard reference to {@link PlayerMobEntity} is safe — playermob is always
 * jarJar'd into Dungeon Train (the same guarantee {@code compat.EchoIdentity}
 * relies on).</p>
 */
public record PlayerMobAppearance(int skinIndex, String skinTextureUrl, boolean slim) {

    /**
     * Snapshot a live PlayerMob's current skin fields. A {@code null} skin URL
     * is normalized to {@code ""} so the value round-trips through the packet's
     * {@code writeUtf}/{@code readUtf} cleanly.
     */
    public static PlayerMobAppearance capture(PlayerMobEntity mob) {
        String url = mob.getSkinTextureUrl();
        return new PlayerMobAppearance(mob.getSkinIndex(), url == null ? "" : url, mob.isSkinSlim());
    }
}
