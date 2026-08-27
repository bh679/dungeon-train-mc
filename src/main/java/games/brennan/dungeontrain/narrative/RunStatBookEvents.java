package games.brennan.dungeontrain.narrative;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/**
 * The two moments in a {@link RunStatBookFactory Faulthurst stat book}'s life that are not the
 * once-a-second refresh: reaching a hand, and being opened.
 *
 * <h2>Why the hand matters</h2>
 * <p>A written book cannot be opened from a chest — it has to be held first. So the hand is the last
 * guaranteed checkpoint before a read, and refreshing there closes the gap the throttled inventory
 * sweep in {@code NarrativeBookEvents.onPlayerTick} leaves: a book picked up and read inside the
 * same second would otherwise still be showing the bare opener-and-follow-up scrap a container
 * bakes, with no number in it at all.</p>
 *
 * <h2>Why the open only locks</h2>
 * <p>It cannot also refresh, and it is worth being clear about why. The book screen is opened
 * CLIENT-side, by {@code WrittenBookItem#use} → {@code LocalPlayer.openItemGui}, from the client's
 * own copy of the stack. {@link PlayerInteractEvent.RightClickItem} reaches the server after that
 * screen is already up, so anything written to the stack here lands too late for the read in
 * progress — the player would see the old number now and the right one only on a second open.</p>
 *
 * <p>So the number is kept current on the way in and merely FROZEN here. What the reader sees is
 * the value as of the last refresh, at most one sweep interval old — invisible on a carriage count
 * or a chest tally — and from this moment it never moves again. The book stops being a dial and
 * becomes a memento of the run as it stood when they read it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class RunStatBookEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RunStatBookEvents() {}

    /**
     * Fill in (or update) the number the moment the book reaches a hand — the last checkpoint before
     * it can be opened. A no-op on a locked book, and on an unchanged one.
     */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EquipmentSlot slot = event.getSlot();
        if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;
        ItemStack stack = event.getTo();
        if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return;
        RunStatBookFactory.refresh(stack, player);
    }

    /**
     * Freeze the page on the first open. Deliberately does not re-bake it — see the class note: the
     * client's screen is already open by the time this fires.
     */
    @SubscribeEvent
    public static void onBookOpen(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return;
        if (!RunStatBookTag.is(stack) || RunStatBookTag.isLocked(stack)) return;

        RunStatBookTag.lock(stack);
        LOGGER.debug("[DungeonTrain] StatBook: locked '{}' at {} for {}",
            RunStatBookTag.subject(stack).map(RunStatSubject::id).orElse("(none)"),
            RunStatBookTag.renderedValue(stack), player.getName().getString());
    }
}
