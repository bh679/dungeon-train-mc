package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String REFUSED_KEY_1 = "chat.dungeontrain.portal.no_shulker_box.1";
    private static final String REFUSED_KEY_2 = "chat.dungeontrain.portal.no_shulker_box.2";

    /** The beat between the two lines. The second is an afterthought, not a second warning. */
    private static final int SECOND_LINE_DELAY_TICKS = 40;

    /**
     * How long a player is left alone after being told.
     *
     * <p>A refused click costs nothing, so a player who keeps trying — or who simply holds the
     * button — would otherwise paper their chat with the same two lines twenty times a second. Long
     * enough to cover a stubborn attempt, short enough that coming back later still explains
     * itself.</p>
     */
    private static final int REPEAT_COOLDOWN_TICKS = 600;

    /** Player → game time they were last told. Server thread only. */
    private static final Map<UUID, Long> LAST_TOLD = new HashMap<>();

    /** Second lines not yet due. Server thread only, and never more than one per player. */
    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(UUID player, long dueAt) {}

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
        refuse(player, target, "click");
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
        refuse(event.getEntity(), event.getPos(), "place");
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
            refuse(event.getEntity(), pos, "multi-place");
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

    /**
     * Say why — in chat, and in the log.
     *
     * <p>Two lines, a beat apart, rather than one action-bar flash: the action bar is where a
     * corridor puts its transient notices, and this is the box refusing, which is a small piece of
     * the world talking back. It reads better arriving the way an advancement does, and it stays in
     * the scrollback for a player who looks away.</p>
     *
     * <p>The log line has no such audience — it exists because a refusal is otherwise invisible
     * after the fact ("nothing was placed" and "nobody tried" read identically), which makes both
     * testing and a bug report guesswork. It is written on every refusal, including the ones the
     * cooldown silences.</p>
     */
    private static void refuse(Entity entity, BlockPos pos, String via) {
        LOGGER.info("[DungeonTrain] Portal: refused a shulker box at {} ({})", pos, via);
        if (!(entity instanceof ServerPlayer player)) return;

        long now = player.serverLevel().getGameTime();
        Long told = LAST_TOLD.get(player.getUUID());
        if (told != null && now - told < REPEAT_COOLDOWN_TICKS) return;
        LAST_TOLD.put(player.getUUID(), now);

        player.sendSystemMessage(Component.translatable(REFUSED_KEY_1).withStyle(ChatFormatting.GRAY));
        PENDING.add(new Pending(player.getUUID(), now + SECOND_LINE_DELAY_TICKS));
    }

    /** Deliver the second lines that have come due, and forget players who have left. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;

        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (now < pending.dueAt()) continue;
            it.remove();

            // A player who logged out between the two lines simply does not hear the second one.
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player != null) {
                player.sendSystemMessage(
                    Component.translatable(REFUSED_KEY_2).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /**
     * Drop a world's worth of state when the server stops, so a second world in the same session
     * does not inherit the first's cooldowns or owe anybody a second line.
     */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        LAST_TOLD.clear();
        PENDING.clear();
    }
}
