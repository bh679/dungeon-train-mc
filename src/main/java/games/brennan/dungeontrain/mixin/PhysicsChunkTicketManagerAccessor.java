package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read access to which chunk sections Sable's physics scene currently holds — the private
 * {@code physicsChunks} map of {@code PhysicsChunkTicketManager}. {@code ColliderBatch} needs it to
 * decide between "remove then re-upload" (tracked) and {@code addSectionIfNotTracked} (not), because
 * removing a section the native scene never had is an unverified path.
 *
 * <p>Read-only; never mutate the map through this. Bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code private final Map<SectionPos, PhysicsChunkTicket> physicsChunks}.
 * <b>Re-verify on any {@code sable_version} bump.</b></p>
 */
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public interface PhysicsChunkTicketManagerAccessor {

    @Accessor("physicsChunks")
    Map<SectionPos, PhysicsChunkTicket> dungeontrain$physicsChunks();
}
