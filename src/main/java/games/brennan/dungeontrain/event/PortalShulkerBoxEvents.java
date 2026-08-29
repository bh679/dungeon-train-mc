package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Keeps shulker boxes out of a dimensional carriage and out of the twin corridor it pairs with.
 *
 * <p>A dimensional carriage exists twice — the corridor riding the train and its static twin at the
 * world floor — and {@link games.brennan.dungeontrain.portal.PortalEditMirror} copies every edit from
 * either to the other so the crossing between them stays invisible. It copies block <i>state</i>
 * only. For a torch that is exactly right and for a chest it is close enough, but a shulker box
 * mirrored as a bare block is a second shulker box: break the copy and it drops, which duplicates the
 * item outright, and anything the player stored in the two boxes diverges between them.</p>
 *
 * <p>Rather than teach the mirror to carry container contents — which would make the dupe into a
 * two-way sync problem across a sub-level boundary — the box simply cannot go down there. Everything
 * else in the corridor stays fully editable, and breaking a box placed before this existed still
 * works, so nothing is stranded.</p>
 *
 * <p><b>Refused at the click, not undone afterwards.</b> The obvious hook,
 * {@link BlockEvent.EntityPlaceEvent}, fires once the block is already in the world: cancelling it
 * puts the box down and takes it away again, which the player sees flicker, and which momentarily
 * feeds a real block change to the mirror — the very thing being prevented. So the primary refusal is
 * {@link PlayerInteractEvent.RightClickBlock}, which runs <i>before</i> the item is used, and denies
 * only the item half ({@code setUseItem}) so right-clicking a chest with a box in hand still opens
 * the chest. The place events below stay as a backstop for placements that never pass through a
 * right-click at all.</p>
 *
 * <p><b>Portal rooms are untouched.</b> A room has no twin and no mirror, so a shulker box in one
 * behaves like a shulker box anywhere else.</p>
 *
 * <p>Both copies are covered by one test: {@link PortalPairIndex#isCorridorCell} resolves the
 * carriage's sub-level plot and the twin's ordinary world blocks from the same position, and it is
 * the same index the mirror itself consults — so "cell the mirror would copy" and "cell that refuses
 * a box" cannot drift apart.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalShulkerBoxEvents {

    private static final String REFUSED_KEY = "chat.dungeontrain.portal.no_shulker_box";

    private PortalShulkerBoxEvents() {}

    /**
     * The refusal that actually matters: deny the click before the box is ever placed.
     *
     * <p>The target cell is derived through {@link BlockPlaceContext}, which is what vanilla itself
     * asks — the face offset, unless the clicked block is replaceable, in which case the click lands
     * on it. Testing the clicked position instead would refuse a box placed against the <i>outside</i>
     * of a corridor wall, which is somewhere it is perfectly welcome.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getUseItem() == TriState.FALSE) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getItemStack().getItem() instanceof BlockItem item)) return;
        if (!(item.getBlock() instanceof ShulkerBoxBlock)) return;

        Player player = event.getEntity();
        BlockPos target = new BlockPlaceContext(
            new UseOnContext(player, event.getHand(), event.getHitVec())).getClickedPos();
        if (!PortalPairIndex.isCorridorCell(level, target)) return;

        event.setUseItem(TriState.FALSE);
        tellPlayer(player);
    }

    /**
     * Backstop for placements that never pass through a right-click — another mod placing on a
     * player's behalf, or a block placed by machinery. Reached only when the click-side refusal did
     * not apply, so in ordinary play this never fires.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!isShulkerBox(event.getPlacedBlock())) return;
        if (!PortalPairIndex.isCorridorCell(level, event.getPos())) return;

        event.setCanceled(true);
        tellPlayer(event.getEntity());
    }

    /**
     * The bulk-placement counterpart. Effortless Building — bundled in the modpack — places whole
     * rows at once through this event, on one right-click that the handler above cannot resolve to
     * the individual cells. The event is all-or-nothing, so one refused cell refuses the placement: a
     * half-laid row leaving boxes on both sides of the corridor wall would be worse than none.
     */
    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Every cell is already in the world by the time this fires, so read each back rather than
        // trusting the event's single placed state — the same approach PortalEditEvents takes.
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos();
            if (!isShulkerBox(level.getBlockState(pos))) continue;
            if (!PortalPairIndex.isCorridorCell(level, pos)) continue;

            event.setCanceled(true);
            tellPlayer(event.getEntity());
            return;
        }
    }

    /**
     * Covers the undyed box, all sixteen dyed variants, and any modded subclass — one test, and no
     * item list or tag to keep in sync as colours come and go.
     */
    private static boolean isShulkerBox(BlockState state) {
        return state.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Say why, on the action bar, in the same voice as the other corridor notices. */
    private static void tellPlayer(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            player.displayClientMessage(
                Component.translatable(REFUSED_KEY).withStyle(ChatFormatting.GRAY), true);
        }
    }
}
