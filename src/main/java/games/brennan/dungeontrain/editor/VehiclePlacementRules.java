package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderBounds;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.template.TemplateDecor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.block.BaseRailBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What placing a vehicle inside a build does, and what the author is told about it.
 *
 * <p>Three rules, all decided at the moment of placement because that is the moment the author
 * can still change their mind — a save that quietly drops the boat, or a carriage whose minecart
 * never moves, is discovered much later and far from the cause:</p>
 * <ul>
 *   <li><b>A minecart places anywhere.</b> Vanilla's {@code MinecartItem} only places onto a
 *       rail; inside a build a cart is furniture, and a builder should not have to lay a rail,
 *       place the cart and pull the rail back up. Right-clicking any non-rail block with a cart
 *       puts one on top of it.</li>
 *   <li><b>A minecart on a rail gets a warning.</b> Aboard a moving carriage a cart on rails does
 *       not run — the rails live in the carriage's sub-level, and a cart reads the block under it
 *       in world space — but one parked off the rails rides along fine. A dimensional carriage
 *       never moves, so there the rails work and nothing is said.</li>
 *   <li><b>A boat is refused, except in a dimensional carriage.</b> It still places — nothing
 *       here cancels a boat — but {@link TemplateDecor#VEHICLE_TYPES} leaves boats out of a
 *       carriage save, so the author is told in red that it will not be kept. Sable carries a
 *       boat onto a moving carriage as loose cargo and it never sits right. A dimensional
 *       carriage's room keeps its boats ({@link TemplateDecor.Rule#ROOM}) and says nothing.</li>
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
public final class VehiclePlacementRules {

    /** A vehicle item raises both interaction events in one click; say it once per second. */
    private static final int THROTTLE_TICKS = 20;

    private static final Map<UUID, Long> LAST_SAID = new ConcurrentHashMap<>();

    /**
     * The kind tag a dimensional carriage's plot carries in its {@link EditorPlotScope#key()} —
     * {@code "<category>:<kind>:<id>:<name>"}, where every room's kind is the portal-room track kind.
     */
    private static final String ROOM_KIND = "portal_room";

    private VehiclePlacementRules() {}

    /** Boats place from {@code BoatItem.use}, which is the empty-hand-on-air event. */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getItemStack().getItem() instanceof BoatItem)) return;
        Build build = buildAround(player, level);
        if (build == null || build.room()) return;
        say(player, level, Component.literal(
                "Boats aren't saved in carriage templates — this one will be gone the next time the build is stamped.")
            .withStyle(ChatFormatting.RED));
    }

    /**
     * Minecarts place from {@code MinecartItem.useOn}, which refuses anything but a rail; inside a
     * build this places the cart on any block instead, and warns when it does land on a rail.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof MinecartItem)) return;
        Build build = buildAround(player, level);
        if (build == null) return;

        if (level.getBlockState(event.getPos()).getBlock() instanceof BaseRailBlock) {
            // Vanilla places it; the warning is the only thing to add, and only where rails fail.
            if (!build.room()) {
                say(player, level, Component.literal(
                        "Minecarts are saved, but they don't run on rails aboard a moving carriage — "
                            + "park one off the rails and it will ride along.")
                    .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        AbstractMinecart.Type type = typeOf(held);
        if (type == null) return;
        BlockPos at = event.getPos().relative(event.getFace());
        AbstractMinecart cart = AbstractMinecart.createMinecart(level,
            at.getX() + 0.5, at.getY() + 0.0625, at.getZ() + 0.5, type, held, player);
        if (cart == null) return;
        cart.setYRot(player.getYRot());
        if (!level.addFreshEntity(cart)) return;
        if (!player.getAbilities().instabuild) held.shrink(1);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * The vanilla cart type behind a minecart item. {@code MinecartItem.type} is package-private,
     * so the vanilla items are matched by identity; a modded cart item is left to its own
     * {@code useOn}.
     */
    @Nullable
    private static AbstractMinecart.Type typeOf(ItemStack held) {
        if (held.is(Items.MINECART)) return AbstractMinecart.Type.RIDEABLE;
        if (held.is(Items.CHEST_MINECART)) return AbstractMinecart.Type.CHEST;
        if (held.is(Items.FURNACE_MINECART)) return AbstractMinecart.Type.FURNACE;
        if (held.is(Items.TNT_MINECART)) return AbstractMinecart.Type.TNT;
        if (held.is(Items.HOPPER_MINECART)) return AbstractMinecart.Type.HOPPER;
        // A command-block minecart is never carried by a template (TemplateDecor.VEHICLE_TYPES),
        // so there is no build to place one in; vanilla keeps its rail rule.
        return null;
    }

    /** Where a save would capture from the player's position, or null when it would not. */
    private record Build(boolean room) {}

    @Nullable
    private static Build buildAround(ServerPlayer player, ServerLevel level) {
        // Plots are not confined to the editor world: `/dungeontrain editor enter` stamps one at
        // sky height in whatever world the author is in, so the plot test comes first and
        // unconditionally — it is a Y early-out and a box lookup, cheap enough for every click.
        if (player.blockPosition().getY() >= EditorLayout.PLOT_Y) {
            var scope = EditorPlotScope.resolveAt(player, level);
            if (scope.isPresent()) {
                return new Build(scope.get().key().toLowerCase(Locale.ROOT).contains(":" + ROOM_KIND + ":"));
            }
        }
        if (level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)
            && BuilderBounds.isInsideBuild(player.blockPosition(), BuilderBounds.volumesFor(level))) {
            return new Build(false);
        }
        return null;
    }

    private static void say(ServerPlayer player, ServerLevel level, Component message) {
        long now = level.getGameTime();
        Long last = LAST_SAID.get(player.getUUID());
        if (last != null && now - last < THROTTLE_TICKS) return;
        LAST_SAID.put(player.getUUID(), now);
        player.sendSystemMessage(message);
    }
}
