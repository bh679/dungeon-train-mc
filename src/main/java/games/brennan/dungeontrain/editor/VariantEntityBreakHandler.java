package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VariantHoverPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;

import java.io.IOException;
import java.util.List;

/**
 * Entity-side counterpart to {@link VariantBlockBreakHandler}. When a player
 * swings at an {@link ArmorStand} or {@link ItemFrame} (incl. glow frames)
 * inside an editor plot, clears any variant sidecar entry and any
 * {@link ContainerContentsStore} loot-prefab link tied to the entity's
 * localPos — so a stand placed via a saved-contents prefab item leaves no
 * stale data behind when broken.
 *
 * <p>Uses {@link AttackEntityEvent} (pre-damage) to mirror the timing of
 * {@code BlockEvent.BreakEvent} (pre-break) — both fire on player intent
 * before the world mutation. In editor mode the player is in creative so
 * one swing = kill; in survival the data clears on first intentional swing
 * even if the stand survives the hit, which is acceptable since the swing
 * is unambiguous intent.</p>
 */
public final class VariantEntityBreakHandler {

    private VariantEntityBreakHandler() {}

        public static void onAttackEntity(net.minecraft.world.entity.player.Player attacker, net.minecraft.world.entity.Entity attackTarget, boolean attackCanceled) {
        if (attackCanceled) return;
        if (!(attacker instanceof ServerPlayer player)) return;
        Entity target = attackTarget;
        if (!(target instanceof ArmorStand) && !(target instanceof ItemFrame)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) return;

        BlockPos worldPos = target.blockPosition();
        BlockPos local = worldPos.subtract(plot.origin());
        if (!plot.inBounds(local)) return;

        int removedVariants = 0;
        List<VariantState> existing = plot.statesAt(local);
        if (existing != null) {
            removedVariants = existing.size();
            plot.remove(local);
            try {
                plot.save();
            } catch (IOException e) {
                player.displayClientMessage(
                    Component.literal("Variant save failed: " + e.getMessage())
                        .withStyle(ChatFormatting.YELLOW), true);
                return;
            }
        }

        ContainerContentsStore store = ContainerContentsStore.loadFor(plot.key());
        String prevLink = store.linkAt(local);
        boolean hadLink = false;
        if (prevLink != null) {
            hadLink = store.clearLink(local);
            if (hadLink) {
                ContainerContentsMenuController.resyncIfOpen(player, plot.key(), local);
            }
        }

        if (removedVariants == 0 && !hadLink) return;

        DungeonTrainNet.sendTo(player, VariantHoverPacket.empty());

        final int lx = local.getX();
        final int ly = local.getY();
        final int lz = local.getZ();
        String entityKind = target instanceof ArmorStand ? "stand" : "frame";
        StringBuilder msg = new StringBuilder("- removed ");
        if (removedVariants > 0) {
            msg.append(removedVariants).append(" variants");
        }
        if (removedVariants > 0 && hadLink) msg.append(" + ");
        if (hadLink) {
            msg.append("link '").append(prevLink).append('\'');
        }
        msg.append(" @ ").append(lx).append(',').append(ly).append(',').append(lz)
            .append(" (").append(entityKind).append(')');
        player.displayClientMessage(
            Component.literal(msg.toString()).withStyle(ChatFormatting.GOLD), true);
    }
}
