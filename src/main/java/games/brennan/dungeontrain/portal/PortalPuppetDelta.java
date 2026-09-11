package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.PortalPuppetsPacket.Entry;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

/**
 * Decides how much of a puppet one viewer needs to be sent this tick.
 *
 * <p>{@link PortalPuppets#describe} builds one {@link Entry#SHAPE_FULL} entry per source per tick
 * and every viewer of the pair shares it. What each viewer is actually <i>sent</i> depends on what
 * their client already holds, and that differs per viewer — one walked in this tick, another has
 * been watching for a minute — so the decision is made per recipient against a memory of the last
 * full entry they were sent. Pure, and kept apart from the level so the rule can be tested.</p>
 */
final class PortalPuppetDelta {

    /**
     * Ticks between unconditional full re-sends of a puppet the viewer already has.
     *
     * <p>The client drops everything if no snapshot arrives for
     * {@code PortalPuppetsClient.STALE_TICKS} (40) — a lag spike, a chunk stall. The server cannot
     * see that happen, and a pose-only entry for a key the client no longer holds draws nothing. The
     * refresh bounds the damage: matched to the client's own timeout, so whatever it discarded is
     * whole again within one period. Sixty-four puppets at one full entry each per forty ticks is
     * less than two full entries a tick, which is nothing.</p>
     */
    static final int REFRESH_TICKS = 40;

    private PortalPuppetDelta() {}

    /**
     * What a viewer was last sent for one key: the full entry as re-posed by every entry since, and
     * the tick of the last full one.
     *
     * <p>The pose is folded into {@code lastFull} rather than held beside it so that a viewer's memory
     * is always a complete description — it is what the client is holding, exactly.</p>
     */
    record Sent(Entry lastFull, long lastFullTick) {

        /** The memory after {@code next} has been sent at {@code tick}. */
        Sent advance(Entry next, long tick) {
            return next.isFull() ? new Sent(next, tick) : new Sent(lastFull.withPose(next), lastFullTick);
        }
    }

    /**
     * The entry to send for {@code next}, given what the viewer holds.
     *
     * <p>Full when they hold nothing, when the appearance changed, or when the refresh is due; a
     * pose when only the pose moved; held otherwise. The order matters only in that a full entry
     * subsumes the others — there is no shape that says "appearance changed but pose did not".</p>
     */
    static Entry classify(Sent sent, Entry next, long tick) {
        if (sent == null) return next;
        if (tick - sent.lastFullTick() >= REFRESH_TICKS) return next;
        if (!sameAppearance(sent.lastFull(), next)) return next;
        if (!samePose(sent.lastFull(), next)) {
            return Entry.pose(next.key(), next.subLevel(), next.x(), next.y(), next.z(),
                next.yaw(), next.headYaw(), next.pitch());
        }
        return Entry.held(next.key());
    }

    /**
     * Whether two full entries would build and dress the same model.
     *
     * <p>The sub-level counts as appearance, not pose: a source crossing from twin to carriage
     * changes which space its coordinates are in, and the client resolves that per entry, so the
     * safe answer is to re-describe it rather than trust a pose-only entry to carry the switch.</p>
     */
    static boolean sameAppearance(Entry a, Entry b) {
        return a.kind() == b.kind()
            && a.typeId().equals(b.typeId())
            && Objects.equals(a.sourceId(), b.sourceId())
            && a.name().equals(b.name())
            && Objects.equals(a.subLevel(), b.subLevel())
            && sameData(a.data(), b.data())
            && ItemStack.matches(a.mainHand(), b.mainHand())
            && ItemStack.matches(a.head(), b.head())
            && ItemStack.matches(a.chest(), b.chest())
            && ItemStack.matches(a.legs(), b.legs())
            && ItemStack.matches(a.feet(), b.feet());
    }

    static boolean samePose(Entry a, Entry b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z()
            && a.yaw() == b.yaw() && a.headYaw() == b.headYaw() && a.pitch() == b.pitch();
    }

    /**
     * Whether two synched-data lists carry the same values.
     *
     * <p>Not {@code List.equals}: {@code SynchedEntityData} copies an {@link ItemStack} value every
     * time it is read, and {@code ItemStack} compares by identity, so an item entity's data would
     * read as changed on every tick and the loot on a corridor floor — the case the delta exists
     * for — would go full-size forever. Item stacks compare by contents; everything else by
     * {@code equals}, which is what the value types (primitives, enums, optionals, poses) mean by
     * it.</p>
     */
    static boolean sameData(List<SynchedEntityData.DataValue<?>> a,
                            List<SynchedEntityData.DataValue<?>> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SynchedEntityData.DataValue<?> va = a.get(i);
            SynchedEntityData.DataValue<?> vb = b.get(i);
            if (va.id() != vb.id()) return false;
            if (!sameValue(va.value(), vb.value())) return false;
        }
        return true;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof ItemStack sa && b instanceof ItemStack sb) return ItemStack.matches(sa, sb);
        return Objects.equals(a, b);
    }
}
