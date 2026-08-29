package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
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
 * <h2>Where a click is read from</h2>
 * <p>{@code event.getPos().relative(event.getFace())} — the cell a real door would have landed in,
 * exactly the way {@code PrefabUseHandler} reads a paste target. That cell only means something when
 * it falls on one of a room's own two doorway columns
 * ({@code origin.x - 1} / {@code origin.x + size.x}, the same columns
 * {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells} ghosts) — anywhere else the click is
 * left alone and plays out as whatever it would have without this handler.</p>
 *
 * <h2>What a hit sets</h2>
 * <p>Both axes at once, from the one click: how far along the wall ({@code Z}, signed, centred —
 * {@link PortalRoomDoorOffset}) and how far up it ({@code Y}, unsigned, from the room's own floor —
 * {@link PortalRoomDoorHeightOffset}). Clamped to whatever slack this room's own width and height
 * actually have, the same clamp the real corridor is built against, so a click past the edge of what
 * is achievable lands exactly on the edge rather than being refused.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalRoomDoorPointer {

    private PortalRoomDoorPointer() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack held = event.getItemStack();
        if (!(held.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof DoorBlock)) {
            return;
        }
        if (EditorStampedCategoryState.current().orElse(null) != EditorCategory.PORTALS) return;

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

        int offset = PortalRoomLayout.clampDoorOffset(dims, match.size().getZ(), match.rawOffset());
        int heightOffset = PortalRoomLayout.clampDoorHeightOffset(
            dims, match.size().getY(), match.rawHeightOffset());

        PortalRoomSettings current = PortalRoomSettings.of(match.name());
        PortalRoomSettings updated = current
            .withDoorOffset(new PortalRoomDoorOffset(offset))
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(heightOffset));

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
        player.displayClientMessage(Component.literal(
            "Dimensional carriage '" + match.name() + "' door position: " + across + ", " + up + "." + note
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

    /** One room's doorway column, matched against a click target, with the raw (unclamped) offsets it implies. */
    private record Match(String name, Vec3i size, int rawOffset, int rawHeightOffset) {}

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
            return new Match(name, size, rawOffset, rawHeightOffset);
        }
        return null;
    }
}
