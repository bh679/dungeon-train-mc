package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.function.Predicate;

/**
 * No advancements are earned on the death screen. {@code mixin/PlayerAdvancementsDeadGateMixin} refuses
 * every award, in every namespace, while the player {@linkplain #isOnDeathScreen lies dead}, with two
 * exceptions:
 * <ul>
 *   <li>{@link #ALLOWED_WHILE_DEAD}: The Great Beyond, which is made for the death screen ("Complete the
 *       feedback survey after death").</li>
 *   <li>Advancements granted by other advancements: the Everything Burrito, each tab-complete and the tab
 *       copies and heads that follow an earned original ({@link #grantedByOthers}). An advancement earned on
 *       the death tick, or The Great Beyond itself, can finish one, and the capstone check runs
 *       {@link DeferredCapstoneGrants#DELAY_TICKS} ticks later, when the player is already on the death
 *       screen.</li>
 * </ul>
 *
 * <p><b>Dead means dead for at least a tick</b> ({@code deathTime > 0}). A totem fires
 * {@code minecraft:used_totem} while health is still 0, before it heals the player, and advancements earned
 * <em>by dying</em> ({@code contained_loop}, Death Note ones) are awarded in the death event itself. Both
 * happen with {@code deathTime == 0} and pass. After respawn the player is a new, living entity.</p>
 *
 * <p>A refused award is lost, not deferred, as for a criterion already granted: a kill landing after the
 * player died does not count, and state criteria (location, inventory, counters) fire again after respawn
 * by themselves.</p>
 */
public final class DeadPlayerAdvancementGate {

    /** Advancements a dead player may still earn by their own action. */
    static final Set<ResourceLocation> ALLOWED_WHILE_DEAD = Set.of(SurveyAdvancement.ID);

    private DeadPlayerAdvancementGate() {}

    /** Is {@code player} lying dead, on the death screen, rather than alive or in the tick it died? */
    public static boolean isOnDeathScreen(ServerPlayer player) {
        return player.isDeadOrDying() && player.deathTime > 0;
    }

    /** Should an award of {@code advancementId} to {@code player} be refused now? */
    public static boolean refuses(ServerPlayer player, ResourceLocation advancementId) {
        return refuses(isOnDeathScreen(player), advancementId, DeadPlayerAdvancementGate::grantedByOthers);
    }

    /**
     * Pure form of {@link #refuses(ServerPlayer, ResourceLocation)}: refused only while dead, and then
     * unless the advancement is allowed while dead or granted by others.
     */
    static boolean refuses(boolean onDeathScreen, ResourceLocation advancementId,
                           Predicate<ResourceLocation> grantedByOthers) {
        if (!onDeathScreen || advancementId == null) return false;
        if (ALLOWED_WHILE_DEAD.contains(advancementId)) return false;
        return !grantedByOthers.test(advancementId);
    }

    /** Granted because other advancements were earned: a capstone, a tab-complete, or a tab copy/head. */
    static boolean grantedByOthers(ResourceLocation id) {
        return CompletionistAdvancement.isDerivedGrant(id) || TabGateways.isLinked(id);
    }
}
