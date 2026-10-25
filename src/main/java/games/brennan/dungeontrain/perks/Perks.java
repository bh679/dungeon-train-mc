package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.client.PerkClientState;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PerkStatePacket;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;

import java.util.Map;

/**
 * The perks API. Effect code anywhere in the mod asks one question:
 * <pre>int r = Perks.rank(player, TierOnePerks.HEALTH);</pre>
 * and scales by {@code r} (0 means "no perk"). Works on the server (reads the attachment) and
 * on the client for the local player (reads the synced mirror; other players read as 0).
 *
 * <p>The mutators are server-only and are what the debug command and the unlock / point
 * earning flows call. Each one persists, syncs the owning client and notifies
 * {@link PerkEffects}.</p>
 */
public final class Perks {

    private Perks() {}

    // ---------------- reads (both sides) ----------------

    /** Active points while unlocked, else 0. Unknown ids and null players read 0. */
    public static int rank(Player player, String perkId) {
        return state(player).rank(perkId);
    }

    public static boolean isUnlocked(Player player, String perkId) {
        return state(player).isUnlocked(perkId);
    }

    /** The whole state, for menus and the command. Never null. */
    public static PerkState state(Player player) {
        if (player == null) return PerkState.EMPTY;
        if (player instanceof ServerPlayer sp) return sp.getData(ModDataAttachments.PERK_STATE.get());
        if (player.level().isClientSide && FMLEnvironment.dist == Dist.CLIENT) {
            return PerkClientState.stateFor(player);
        }
        return PerkState.EMPTY;
    }

    // ---------------- writes (server only) ----------------

    public static void unlock(ServerPlayer player, String perkId) {
        requireKnown(perkId);
        apply(player, state(player).withUnlocked(perkId));
    }

    /** Set active points (clamped to the perk's max rank). Does not unlock. */
    public static void setActive(ServerPlayer player, String perkId, int points) {
        PerkDef def = requireKnown(perkId);
        apply(player, state(player).withActive(perkId, def.clampRank(points)));
    }

    /** Add active points (clamped). Returns the new rank. */
    public static int addActive(ServerPlayer player, String perkId, int delta) {
        PerkDef def = requireKnown(perkId);
        PerkState before = state(player);
        PerkState after = before.withActive(perkId, def.clampRank(before.activePoints(perkId) + delta));
        apply(player, after);
        return after.rank(perkId);
    }

    /** New life: every active point goes, the unlocked set stays. */
    public static void clearActive(ServerPlayer player) {
        apply(player, state(player).clearedActive());
    }

    /** Dev only: forget everything. */
    public static void clearAll(ServerPlayer player) {
        apply(player, PerkState.EMPTY);
    }

    /** Push the current state to the owning client. */
    public static void sync(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new PerkStatePacket(state(player)));
    }

    /** Called by the lifecycle once a fresh player entity has its state synced. */
    static void playerReady(ServerPlayer player) {
        PerkEffects.firePlayerReady(player);
    }

    private static void apply(ServerPlayer player, PerkState next) {
        PerkState prev = state(player);
        if (prev.equals(next)) return;
        player.setData(ModDataAttachments.PERK_STATE.get(), next);
        sync(player);
        fireChanges(player, prev, next);
    }

    private static void fireChanges(ServerPlayer player, PerkState prev, PerkState next) {
        java.util.Set<String> touched = new java.util.LinkedHashSet<>();
        touched.addAll(prev.active().keySet());
        touched.addAll(next.active().keySet());
        touched.addAll(prev.unlocked());
        touched.addAll(next.unlocked());
        for (String id : touched) {
            PerkEffects.fireRankChanged(player, id, prev.rank(id), next.rank(id));
        }
    }

    private static PerkDef requireKnown(String perkId) {
        PerkRegistry.bootstrap();
        return PerkRegistry.get(perkId)
            .orElseThrow(() -> new IllegalArgumentException("unknown perk id: " + perkId));
    }

    /** For log lines: {@code health=3 speed=1}. */
    public static String describe(PerkState state) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : state.active().entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
            if (!state.isUnlocked(e.getKey())) sb.append("(locked)");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
