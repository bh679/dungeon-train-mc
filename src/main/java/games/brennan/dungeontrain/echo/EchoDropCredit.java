package games.brennan.dungeontrain.echo;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.ChanceKind;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.dungeontrain.compat.EchoIdentity;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.mixin.LivingEntityLastEquipmentAccessor;
import games.brennan.dungeontrain.narrative.BookSafeText;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.player.SourceProfileSkin;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes a credit line into the description of an item an echo lets go of, naming the player the
 * echo embodies — "Once wielded by the echo of Steve". Wording comes from {@link EchoCreditText},
 * placement from {@link EchoCreditLore}.
 *
 * <p>Credited: equipped gear dropped at death (worded by whether a player made the kill), a piece
 * dropped mid-swap to better gear, and gifts. Never credited: plain PlayerMobs, stackable items
 * (lore would stop them stacking with ordinary copies), and backpack loot spilled on death.</p>
 *
 * <p>Each item remembers which players' echoes have credited it ({@link #CREDITS_KEY} in
 * {@code CUSTOM_DATA}); an echo of a player already on that list adds nothing.</p>
 *
 * <p>Cosmetic, so every entry point is no-throw — a failure leaves the item exactly as dropped.</p>
 */
public final class EchoDropCredit {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code CUSTOM_DATA} key: list of {@code {uuid, line}}, oldest credit first. */
    static final String CREDITS_KEY = "dungeontrain_echo_credits";
    private static final String UUID_KEY = "uuid";
    private static final String LINE_KEY = "line";

    /** Longest player name baked into a credit; Minecraft names cap at 16, remote ones are untrusted. */
    private static final int MAX_NAME_LENGTH = 32;

    private EchoDropCredit() {}

    /**
     * An echo is about to drop {@code stack} into the world ({@code Entity.spawnAtLocation}). Credits
     * it when it is gear the echo had equipped: at death the stack is still in its slot; mid-swap it
     * matches the slot's last-tick snapshot, since the replacement went in this same tick.
     */
    public static void onDrop(PlayerMobEntity mob, ItemStack stack) {
        try {
            if (mob.level().isClientSide() || !eligible(stack)) return;
            boolean dying = mob.isDeadOrDying();
            if (!wasEquipped(mob, stack, dying)) return;
            credit(mob, stack, dying ? deathCause(mob.getKillCredit()) : EchoCreditText.Cause.SWAP);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] echo drop credit failed: {}", t.toString());
        }
    }

    /** An echo tossed {@code stack} to someone as a gift. */
    public static void onGift(PlayerMobEntity mob, ItemStack stack) {
        try {
            if (mob.level().isClientSide() || !eligible(stack)) return;
            credit(mob, stack, EchoCreditText.Cause.GIFT);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] echo gift credit failed: {}", t.toString());
        }
    }

    /** Feature on, and a single non-stackable item. */
    private static boolean eligible(ItemStack stack) {
        return DungeonTrainCommonConfig.isEchoDropCreditEnabled()
                && !stack.isEmpty()
                && stack.getMaxStackSize() == 1;
    }

    private static boolean wasEquipped(PlayerMobEntity mob, ItemStack stack, boolean dying) {
        LivingEntityLastEquipmentAccessor last = (LivingEntityLastEquipmentAccessor) mob;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (mob.getItemBySlot(slot) == stack) return true;
            // Dying: identity only. Equipment drops first and leaves last tick's snapshot behind, so an
            // identical spare spilling from the backpack afterwards would otherwise match it.
            if (dying) continue;
            ItemStack previous = switch (slot.getType()) {
                case HAND -> last.dungeontrain$getLastHandItem(slot);
                case HUMANOID_ARMOR -> last.dungeontrain$getLastArmorItem(slot);
                default -> ItemStack.EMPTY;
            };
            if (!previous.isEmpty() && ItemStack.isSameItemSameComponents(previous, stack)) return true;
        }
        return false;
    }

    /**
     * Grim when a player gets the kill credit; Wistful for anything else. Uses the kill credit (a player
     * who hurt the echo in the last 100 ticks — vanilla's own "killed by player" rule) rather than
     * {@code getLastDamageSource()}, which vanilla only assigns after {@code die()} has dropped the loot.
     */
    static EchoCreditText.Cause deathCause(LivingEntity killCredit) {
        return killCredit instanceof Player
                ? EchoCreditText.Cause.KILLED_BY_PLAYER
                : EchoCreditText.Cause.DIED;
    }

    private static void credit(PlayerMobEntity mob, ItemStack stack, EchoCreditText.Cause cause) {
        Optional<SourceProfileSkin.Ref> source = EchoIdentity.sourceProfile(mob);
        if (source.isEmpty()) return;
        UUID player = source.get().uuid();
        String name = BookSafeText.sanitizeAndClampName(source.get().name(), MAX_NAME_LENGTH);
        if (name == null || name.isBlank()) return;

        ListTag credits = readCredits(stack);
        if (alreadyCredited(credits, player)) return;

        String line = EchoCreditText.compose(cause, name, mob.getRandom()::nextInt);
        writeLore(stack, line, priorLines(credits));
        writeCredits(stack, appended(credits, player, line));
    }

    private static void writeLore(ItemStack stack, String line, List<String> priorCredits) {
        List<Component> lines = new ArrayList<>(stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        List<String> plain = lines.stream().map(Component::getString).toList();
        int slot = EchoCreditLore.slotFor(plain, priorCredits);
        Component styled = styled(line);
        if (slot == EchoCreditLore.APPEND) {
            lines.add(styled);
        } else {
            lines.set(slot, styled);
        }
        stack.set(DataComponents.LORE, new ItemLore(List.copyOf(lines)));
    }

    /** Same colour AIN gives its "Crafted by …" lines, so provenance reads as one family. */
    private static Component styled(String line) {
        MutableComponent text = Component.literal(line);
        Optional<ChatFormatting> color = NamingConfig.colorFor(ChanceKind.CRAFTED_DESCRIPTION);
        return color.isPresent() ? text.withStyle(color.get()) : text;
    }

    private static ListTag readCredits(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.getList(CREDITS_KEY, Tag.TAG_COMPOUND);
    }

    private static boolean alreadyCredited(ListTag credits, UUID player) {
        for (int i = 0; i < credits.size(); i++) {
            CompoundTag entry = credits.getCompound(i);
            if (entry.hasUUID(UUID_KEY) && player.equals(entry.getUUID(UUID_KEY))) return true;
        }
        return false;
    }

    private static List<String> priorLines(ListTag credits) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < credits.size(); i++) {
            out.add(credits.getCompound(i).getString(LINE_KEY));
        }
        return out;
    }

    private static ListTag appended(ListTag credits, UUID player, String line) {
        ListTag out = credits.copy();
        CompoundTag entry = new CompoundTag();
        entry.put(UUID_KEY, NbtUtils.createUUID(player));
        entry.putString(LINE_KEY, line);
        out.add(entry);
        return out;
    }

    private static void writeCredits(ItemStack stack, ListTag credits) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(CREDITS_KEY, credits));
    }
}
