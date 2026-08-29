package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalCarriageRole;
import games.brennan.dungeontrain.portal.PortalRoomDoorHeightOffset;
import games.brennan.dungeontrain.portal.PortalRoomDoorOffset;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.io.IOException;

/**
 * Point a room's door at wherever an author right-clicks with one in hand — no door is placed, no
 * ground is needed underneath. Same idea as {@code PrefabUseHandler}'s prefab paste: the door in
 * hand is a pointer, not a block to place.
 *
 * <h2>Why not a placed block</h2>
 * <p>A door needs a solid block directly beneath its own position to survive — that is vanilla
 * physics, not something DungeonTrain controls. A column that is solid enough to hold a door at
 * every height would have no air left at any height for the door itself to occupy: the two
 * requirements — solid below, air here — contradict each other on adjacent rows of the same column.
 * True freedom to set the door anywhere along the wall is therefore only reachable by never actually
 * placing one there.</p>
 *
 * <h2>Where a click is read from — two events, because a ghost is not a block</h2>
 * <p>A click that lands on a real block (the room's end wall) arrives as
 * {@link PlayerInteractEvent.RightClickBlock}, and the cell in front of the clicked face is the
 * target — exactly the way {@code PrefabUseHandler} reads a paste target. But the doorway column is
 * <b>air</b>, and a ghost is drawn rather than placed, so aiming straight at the marker hits nothing
 * and vanilla fires {@link PlayerInteractEvent.RightClickItem} instead. Both are handled: the empty
 * click walks the player's own look ray and takes the first doorway-column cell along it. Handling
 * only the block form is a bug that reads as the feature being dead, since aiming at the ghost — the
 * obvious gesture — is precisely the case it misses.</p>
 *
 * <p>Either way the target only means something when it falls on one of a room's own two doorway
 * columns ({@code origin.x - 1} / {@code origin.x + size.x}, the same columns
 * {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells} ghosts) — anywhere else the click is
 * left alone and plays out as whatever it would have without this handler.</p>
 *
 * <h2>What a hit sets</h2>
 * <p>Both axes at once, from the one click: how far along the wall ({@code Z}, signed, centred —
 * {@link PortalRoomDoorOffset}) and how far up it ({@code Y}, unsigned, from the room's own floor —
 * {@link PortalRoomDoorHeightOffset}). Clamped to whatever slack this room's own width and height
 * actually have, the same clamp the real corridor is built against, so a click past the edge of what
 * is achievable lands exactly on the edge rather than being refused.</p>
 *
 * <h2>Which door — the column clicked, and nothing else</h2>
 * <p>A room has two doorways and they may stand apart. Which one a click sets is already in the
 * click: the near column ({@code origin.x - 1}) is the <b>entry</b> mouth, the far column
 * ({@code origin.x + size.x}) the <b>exit</b> one, and {@link #resolve} has always had to tell them
 * apart to know it was on a doorway column at all. So there is no mode to arm, no second item and no
 * screen — an author aims at the door they mean and clicks it, which is what they were already
 * doing. Setting the entry door on a room whose two doors still agree moves both, because a room
 * that has not chosen to have two doorways is not silently given them; see
 * {@code PortalRoomSettings.withDoorOffset}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalRoomDoorPointer {

    /** How far along the look ray an air-click is honoured, in blocks — creative's own build reach. */
    private static final double REACH = 6.0;

    /** Ray-march step, in blocks. Half a cell, so no cell along the ray is stepped over. */
    private static final double STEP = 0.5;

    private PortalRoomDoorPointer() {}

    /**
     * A click that landed on a real block — the room's own end wall, most often. The cell in front of
     * the face clicked is the one a real door would have gone in, so that is what is aimed at.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!holdingDoor(event.getItemStack())) return;
        if (event.getLevel().isClientSide()) return;

        ServerLevel level = (ServerLevel) event.getLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockPos target = event.getPos().relative(event.getFace());

        Match match = resolve(target, dims);
        if (match == null) return;
        // Cancel first: whatever this click would otherwise have done (placing the door, opening a
        // gate, whatever the targeted block does on right-click) is superseded the moment it lands on
        // a doorway column — a door in hand here always means "set the position", never "place".
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
        apply(player, dims, match);
    }

    /**
     * A click that hit nothing — which is the <b>normal</b> way to aim at a ghost, since the doorway
     * column is air and a ghost is drawn, not placed. Vanilla fires this instead of
     * {@link PlayerInteractEvent.RightClickBlock} when the crosshair is over no block, so without it
     * aiming straight at the marker does nothing at all and only clipping the wall beside it works.
     *
     * <p>Resolved by walking the player's own look ray rather than off a block face, since there is no
     * face to read: the first cell along it that belongs to a doorway column is the one meant.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!holdingDoor(event.getItemStack())) return;
        if (event.getLevel().isClientSide()) return;

        ServerLevel level = (ServerLevel) event.getLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        Match match = resolveAlongLook(player, dims);
        if (match == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
        apply(player, dims, match);
    }

    /** True while the stamped category is PORTALS and the stack is a door of any kind. */
    private static boolean holdingDoor(ItemStack held) {
        if (!(held.getItem() instanceof BlockItem blockItem)) return false;
        if (!(blockItem.getBlock() instanceof DoorBlock)) return false;
        return EditorStampedCategoryState.current().orElse(null) == EditorCategory.PORTALS;
    }

    /**
     * The first doorway-column cell along {@code player}'s look ray, within reach — or null if the
     * ray leaves reach without crossing one.
     *
     * <p>Stepped at half a block so no cell on the way is skipped, and de-duplicated so each cell is
     * tested once however many steps land inside it. {@link #REACH} is creative's build reach, which
     * is what an author is standing at when they aim at a ghost.</p>
     */
    private static Match resolveAlongLook(ServerPlayer player, CarriageDims dims) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        BlockPos previous = null;
        for (double distance = 0.0; distance <= REACH; distance += STEP) {
            BlockPos cell = BlockPos.containing(eye.add(look.scale(distance)));
            if (cell.equals(previous)) continue;
            previous = cell;
            Match match = resolve(cell, dims);
            if (match != null) return match;
        }
        return null;
    }

    /** Clamp both offsets to what this room can spend, persist them, and say what happened. */
    private static void apply(ServerPlayer player, CarriageDims dims, Match match) {
        int offset = PortalRoomLayout.clampDoorOffset(dims, match.size().getZ(), match.rawOffset());
        int heightOffset = PortalRoomLayout.clampDoorHeightOffset(
            dims, match.size().getY(), match.rawHeightOffset());

        PortalRoomSettings current = PortalRoomSettings.of(match.name());
        PortalRoomSettings updated = match.role() == PortalCarriageRole.ENTRY
            ? current
                .withDoorOffset(new PortalRoomDoorOffset(offset))
                .withDoorHeightOffset(new PortalRoomDoorHeightOffset(heightOffset))
            : current
                .withExitDoorOffset(new PortalRoomDoorOffset(offset))
                .withExitDoorHeightOffset(new PortalRoomDoorHeightOffset(heightOffset));

        try {
            TrackVariantWeights.setMode(TrackKind.PORTAL_ROOM, match.name(), updated.toTag());
        } catch (IOException e) {
            player.displayClientMessage(Component.literal(
                "Could not set door position for '" + match.name() + "': " + e.getMessage())
                .withStyle(ChatFormatting.RED), true);
            return;
        }

        String across = offset == 0 ? "centred" : (offset > 0 ? "+" + offset : Integer.toString(offset));
        String up = heightOffset == 0 ? "at the floor"
            : heightOffset + " block" + (heightOffset == 1 ? "" : "s") + " up";
        String note = clampNote(dims, match, offset, heightOffset);
        // Name the door, not just the room. The two ends can stand apart now, so a message that said
        // only "door position" would leave an author unable to tell which of their two clicks landed.
        String which = match.role() == PortalCarriageRole.ENTRY ? "entry" : "exit";
        player.displayClientMessage(Component.literal(
            "Dimensional carriage '" + match.name() + "' " + which + " door position: "
                + across + ", " + up + "." + note
        ).withStyle(note.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
    }

    /**
     * Why a click did not land where it was aimed, when it did not — or empty when it did.
     *
     * <p>Worth saying out loud, and the reason this method exists at all: a room is only free to move
     * its door through the slack it has over {@link PortalRoomLayout#minWidth} /
     * {@link PortalRoomLayout#minHeight}, because the corridor's own cross-section has to stay inside
     * the room or its mouth cannot be sealed. <b>The built-in room has zero height slack</b> — it is
     * exactly as tall as the corridor — so every vertical click on a stock room clamps to the floor
     * and, without this note, reads as the feature being broken rather than as the room needing to be
     * taller. Naming the axis and the fix turns a dead control into an instruction.</p>
     */
    private static String clampNote(CarriageDims dims, Match match, int offset, int heightOffset) {
        boolean acrossClamped = offset != match.rawOffset();
        boolean upClamped = heightOffset != match.rawHeightOffset();
        if (!acrossClamped && !upClamped) return "";

        int widthSlack = PortalRoomLayout.maxDoorOffset(dims, match.size().getZ());
        int heightSlack = PortalRoomLayout.maxDoorHeightOffset(dims, match.size().getY());
        if (acrossClamped && upClamped) {
            return " Clamped on both axes — this room has " + widthSlack + " block"
                + (widthSlack == 1 ? "" : "s") + " of slack across and " + heightSlack + " up."
                + " Widen and heighten it to move the door further.";
        }
        if (acrossClamped) {
            return " Clamped across — this room has " + widthSlack + " block"
                + (widthSlack == 1 ? "" : "s") + " of slack either way. Widen it to move the door further.";
        }
        return " Clamped up — this room has " + heightSlack + " block"
            + (heightSlack == 1 ? "" : "s") + " of slack above the corridor."
            + " Make it taller to raise the door.";
    }

    /**
     * One room's doorway column, matched against a click target, with the raw (unclamped) offsets it
     * implies and which of the room's two doorways it is.
     */
    private record Match(String name, Vec3i size, PortalCarriageRole role, int rawOffset,
                         int rawHeightOffset) {}

    /**
     * Which portal room's doorway column {@code target} falls on, or {@code null} if it matches
     * none — every registered room is checked, not just the one the player happens to be standing
     * in, since the click alone is what says which room and where on it.
     */
    private static Match resolve(BlockPos target, CarriageDims dims) {
        for (String name : PortalRoomEditor.names()) {
            BlockPos origin = PortalRoomEditor.plotOrigin(name, dims);
            if (origin == null) continue;
            Vec3i size = PortalRoomEditor.plotSize(name, dims);
            if (size.getZ() <= 2 || size.getY() <= 2) continue;

            boolean nearColumn = target.getX() == origin.getX() - 1;
            boolean farColumn = target.getX() == origin.getX() + size.getX();
            if (!nearColumn && !farColumn) continue;

            int minZ = origin.getZ() + 1;
            int maxZ = origin.getZ() + size.getZ() - 2;
            if (target.getZ() < minZ || target.getZ() > maxZ) continue;

            int minY = origin.getY() + 1;
            int maxY = origin.getY() + size.getY() - 2;
            if (target.getY() < minY || target.getY() > maxY) continue;

            int centreZ = origin.getZ() + 1 + (size.getZ() - 2) / 2;
            int rawOffset = target.getZ() - centreZ;
            int rawHeightOffset = target.getY() - (origin.getY() + 1);
            // The near column is the entry mouth and the far column the exit one — the same pair of
            // columns PortalRoomDoorCells ghosts, in the same order, and the same rule
            // stampCorridorHalf seals them by.
            PortalCarriageRole role =
                nearColumn ? PortalCarriageRole.ENTRY : PortalCarriageRole.EXIT;
            return new Match(name, size, role, rawOffset, rawHeightOffset);
        }
        return null;
    }
}
