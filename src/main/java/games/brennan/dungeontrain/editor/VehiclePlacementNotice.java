package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderBounds;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.template.TemplateDecor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.block.BaseRailBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells an author, as they place a vehicle inside a build, what the save will make of it.
 *
 * <p>Two vehicles, two different answers, both said at the moment of placement because that is
 * the moment the author can still change their mind — a save that quietly drops the boat, or a
 * carriage whose minecart never moves, is discovered much later and far from the cause:</p>
 * <ul>
 *   <li><b>A boat is refused.</b> It still places — this handler never cancels — but
 *       {@link TemplateDecor#VEHICLE_TYPES} leaves boats out of every save, so the author is told
 *       in red that it will not be kept. Sable carries a boat onto a moving carriage as loose
 *       cargo and it never sits right; saving one would ship something broken on arrival.</li>
 *   <li><b>A minecart is kept, with a warning.</b> Aboard a moving carriage a minecart on rails
 *       does not run — the rails live in the carriage's sub-level, and a cart reads the block
 *       under it in world space — but one parked off the rails rides along fine. The warning is
 *       only raised when the cart is actually being placed on a rail, since that is the only way
 *       a minecart item places at all.</li>
 * </ul>
 *
 * <p>Scoped to where a save will capture: an editor plot of any kind ({@link EditorPlotScope}) or
 * the build volume of a builder world ({@link BuilderBounds}). A boat rowed across the editor's
 * sea between plots is nobody's business.</p>
 *
 * <p>Plain {@link Component#literal} like the rest of the editor's own voice — the editor is a
 * creative-only tool and speaks English throughout; a lang key here would be the only one.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VehiclePlacementNotice {

    /** A vehicle item raises both interaction events in one click; say it once per second. */
    private static final int THROTTLE_TICKS = 20;

    private static final Map<UUID, Long> LAST_SAID = new ConcurrentHashMap<>();

    private VehiclePlacementNotice() {}

    /** Boats place from {@code BoatItem.use}, which is the empty-hand-on-air event. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getItemStack().getItem() instanceof BoatItem)) return;
        if (!insideABuild(player, level)) return;
        say(player, level, Component.literal(
                "Boats aren't saved in templates — this one will be gone the next time the build is stamped.")
            .withStyle(ChatFormatting.RED));
    }

    /** Minecarts place from {@code MinecartItem.useOn}, and only onto a rail. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ItemStack held = event.getItemStack();
        if (held.getItem() instanceof BoatItem) {
            // A boat aimed at a block still places through BoatItem.use, which fires the
            // RightClickItem event above after this one; nothing to add here.
            return;
        }
        if (!(held.getItem() instanceof MinecartItem)) return;
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof BaseRailBlock)) return;
        if (!insideABuild(player, level)) return;
        say(player, level, Component.literal(
                "Minecarts are saved, but they don't run on rails aboard a moving carriage — "
                    + "park one off the rails and it will ride along.")
            .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Whether a save made from where the player stands would capture what they are placing —
     * an editor plot, or a builder world's build volume.
     */
    private static boolean insideABuild(ServerPlayer player, ServerLevel level) {
        // Plots are not confined to the editor world: `/dungeontrain editor enter` stamps one at
        // sky height in whatever world the author is in, so the plot test comes first and
        // unconditionally — it is a Y early-out and a box lookup, cheap enough for every click.
        if (EditorPlotScope.isInsideAnyPlot(player)) return true;
        if (level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return BuilderBounds.isInsideBuild(player.blockPosition(), BuilderBounds.volumesFor(level));
        }
        return false;
    }

    private static void say(ServerPlayer player, ServerLevel level, Component message) {
        long now = level.getGameTime();
        Long last = LAST_SAID.get(player.getUUID());
        if (last != null && now - last < THROTTLE_TICKS) return;
        LAST_SAID.put(player.getUUID(), now);
        player.sendSystemMessage(message);
    }
}
