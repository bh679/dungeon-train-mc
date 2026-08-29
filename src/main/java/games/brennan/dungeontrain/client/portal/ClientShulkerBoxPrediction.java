package games.brennan.dungeontrain.client.portal;

import dev.ryanhcode.sable.Sable;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.joml.Vector3d;

/**
 * Stops the client from <i>predicting</i> a shulker box onto a carriage, so a box the server is
 * about to refuse never appears at all.
 *
 * <h2>Why the server-side refusal isn't enough on its own</h2>
 * <p>{@code MultiPlayerGameMode.useItemOn} places the block locally the instant you click and only
 * afterwards hears what the server made of it. So a corridor refusal — however early it runs on the
 * server — is still a box that appeared and then vanished a moment later. The only way to have
 * nothing appear is for the client to decline to predict, which it can only do on what it knows by
 * itself.</p>
 *
 * <h2>What the client can know, and what it can't</h2>
 * <p>It cannot know a <b>corridor</b>. Corridor bounds live on the server, and
 * {@link games.brennan.dungeontrain.client.ClientPortalCrossing} documents why they stay there: a
 * box the client could test against "would have to be re-sent every tick as the train moved", which
 * is the cost that design exists to avoid. Syncing them for this would be a large price for a
 * one-frame flicker.</p>
 *
 * <p>It <i>can</i> cheaply know a <b>carriage</b> — {@code Sable.HELPER.getContainingClient} resolves
 * the sub-level owning a shipyard position, the same lookup
 * {@link games.brennan.dungeontrain.client.ClientCarriedStatics} makes every tick. So prediction is
 * declined for a shulker box aimed at any carriage, which covers every corridor without knowing
 * which carriages are corridors.</p>
 *
 * <p><b>The trade this makes.</b> Declining to predict is not refusing: the click is still sent, and
 * a box aimed at an ordinary carriage is still placed — it simply appears when the server says so
 * rather than immediately. In singleplayer that is the next tick. On a server it is one round trip,
 * the same wait vanilla gives you for anything the client cannot predict. Paid only by shulker boxes,
 * and only on the train.</p>
 *
 * <p><b>Not covered:</b> a twin corridor is ordinary world blocks, not a sub-level, so a box aimed at
 * one is still predicted and still flickers when the server refuses it. Twin space is somewhere a
 * player reaches by teleporting rather than by playing, so it is left as it is rather than paid for
 * with a synced region test.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientShulkerBoxPrediction {

    private ClientShulkerBoxPrediction() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseItem() == TriState.FALSE) return;
        // Client only. The server has its own, authoritative refusal in PortalShulkerBoxEvents; this
        // one decides nothing and must never be mistaken for the rule.
        if (!event.getLevel().isClientSide()) return;
        if (!(event.getItemStack().getItem() instanceof BlockItem item)) return;
        if (!(item.getBlock() instanceof ShulkerBoxBlock)) return;

        // The cell vanilla would place into, not the one clicked — the same derivation the server
        // side makes, so the two agree about which block is in question.
        BlockPos target = new BlockPlaceContext(
            new UseOnContext(event.getEntity(), event.getHand(), event.getHitVec())).getClickedPos();
        if (Sable.HELPER.getContainingClient(
                new Vector3d(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5)) == null) {
            return;
        }

        event.setUseItem(TriState.FALSE);
    }
}
