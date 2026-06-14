package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops Dungeon Train <em>gameplay</em> advancements from being earned in any
 * mode other than survival or adventure — closing the "flip to creative to
 * cheese achievements" hole.
 *
 * <p>Every advancement criterion, vanilla or modded, is recorded through the
 * same vanilla path: {@link SimpleCriterionTrigger#trigger} loops over its
 * matched listeners and calls {@code listener.run(advancements)}, which in turn
 * calls {@code PlayerAdvancements.award(advancement, criterion)}. We wrap that
 * single {@code run} call so we can see <em>both</em> the player (for the game
 * mode) and the advancement the listener belongs to (for the namespace/tab
 * check) before the award happens.</p>
 *
 * <p>Block rule — skip the award when:
 * <pre>{@code
 * (mode == CREATIVE || mode == SPECTATOR)
 *     && id.namespace == "dungeontrain"
 *     && !id.path.startsWith("editor/")
 * }</pre>
 * The {@code editor/} carve-out keeps the editor tab earnable in creative (the
 * in-game editor is used in creative); there are no top-level
 * {@code dungeontrain:*} advancements, so "not editor" means the gameplay tab
 * (and any future non-editor DT tab). Vanilla and other-mod advancements fall
 * through the namespace check untouched.</p>
 *
 * <p>Filtering by advancement <em>id</em> rather than trigger <em>type</em> is
 * required: {@code dungeontrain:editor_action} backs both editor advancements
 * (allow) and a few {@code dungeon_train/} ones (block), and some
 * {@code dungeon_train/} advancements are backed by the vanilla
 * {@code minecraft:player_killed_entity} trigger. Both still funnel through this
 * one {@code run} call.</p>
 *
 * <p>Gating here (the live criterion path) deliberately leaves two direct
 * {@code PlayerAdvancements.award} callers alone, since they bypass
 * {@code trigger}: login replay from {@code GlobalAchievementStore} (so earned
 * advancements still restore when logging into a creative world) and the
 * {@code /advancement grant} command (so operators can still force-grant in any
 * mode). Because the gate is per-criterion, partial progress on multi-criterion
 * advancements also can't accumulate in creative/spectator.</p>
 */
@Mixin(SimpleCriterionTrigger.class)
public abstract class SimpleCriterionTriggerGameModeMixin {

    @WrapOperation(
        method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Predicate;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/advancements/CriterionTrigger$Listener;run(Lnet/minecraft/server/PlayerAdvancements;)V")
    )
    private void dungeontrain$gateByGameMode(
        CriterionTrigger.Listener<?> listener,
        PlayerAdvancements advancements,
        Operation<Void> original,
        @Local(argsOnly = true) ServerPlayer player
    ) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
            ResourceLocation id = listener.advancement().id();
            if (DungeonTrain.MOD_ID.equals(id.getNamespace())
                && !id.getPath().startsWith("editor/")) {
                return; // Blocked: DT gameplay advancements require survival/adventure.
            }
        }
        original.call(listener, advancements);
    }
}
