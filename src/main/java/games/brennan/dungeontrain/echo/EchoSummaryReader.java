package games.brennan.dungeontrain.echo;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.player.PlayerMobAppearance;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.compat.ReincarnationCaptureGate;
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ReactionChannel;
import games.brennan.playermob.player.GlobalLifeStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the echo a death just left behind out of PlayerMob's {@link GlobalLifeStore} and condenses
 * it into an {@link EchoSummary} for the death screen.
 *
 * <p><b>Timing.</b> PlayerMob builds the echo from a mixin at the HEAD of {@code ServerPlayer.die},
 * before NeoForge fires {@code LivingDeathEvent}, so by the time {@code RunStatsEvents.onPlayerDeath}
 * runs, this death's record is the player's newest. When PlayerMob's capture gate vetoes a death no
 * record is written and the newest one belongs to an earlier life, so this returns {@code null}
 * whenever the gate says no — the page is skipped rather than showing the wrong echo.</p>
 *
 * <p>The snapshot is loaded into a throwaway {@link PlayerMobEntity} that is never added to the
 * world, so the look, gear and backpack come out exactly as PlayerMob will spawn them.</p>
 */
public final class EchoSummaryReader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EquipmentSlot[] ARMOUR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private EchoSummaryReader() {
    }

    /** This death's echo, or {@code null} when none was captured or it can't be read. */
    public static EchoSummary read(ServerPlayer player) {
        try {
            if (!ReincarnationCaptureGate.shouldCapture(player)) {
                return null;
            }
            MinecraftServer server = player.getServer();
            if (server == null || PlayerMobRegistry.PLAYER_MOB == null) {
                return null;
            }
            List<GlobalLifeStore.DeathRecord> records = GlobalLifeStore.get(server).recordsForPlayer(player.getUUID());
            if (records.isEmpty()) {
                return null;
            }
            GlobalLifeStore.DeathRecord record = records.get(records.size() - 1);
            return fromSnapshot(player, record.snapshot());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read the echo for {}'s death page", player.getName().getString(), e);
            return null;
        }
    }

    private static EchoSummary fromSnapshot(ServerPlayer player, CompoundTag snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        PlayerMobEntity ghost = new PlayerMobEntity(PlayerMobRegistry.PLAYER_MOB, player.serverLevel());
        ghost.applyCustomData(snapshot.copy());
        PlayerMobAppearance appearance = PlayerMobAppearance.capture(ghost);
        DispositionTraits traits = new DispositionTraits();
        traits.load(snapshot);
        List<Integer> skills = new ArrayList<>();
        for (ReactionChannel channel : SKILL_CHANNELS) {
            skills.add(traits.reactionSpeed(channel));
        }
        return new EchoSummary(appearance, traits.fightFlight(), traits.friendliness(),
                traits.reactionSpeed(), skills, carried(ghost), armour(ghost));
    }

    /** PlayerMob's channel for each {@link EchoPersonality.Skill}, in the same order. */
    private static final ReactionChannel[] SKILL_CHANNELS = {
        ReactionChannel.MELEE, ReactionChannel.RANGED, ReactionChannel.DEFENCE,
        ReactionChannel.BUILDING, ReactionChannel.MOVEMENT, ReactionChannel.ITEMS
    };

    /** The backpack's non-empty stacks, in slot order (food first, as PlayerMob packs it). */
    private static List<ItemStack> carried(PlayerMobEntity ghost) {
        SimpleContainer bag = ghost.getInventory();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < bag.getContainerSize() && out.size() < EchoSummary.MAX_CARRIED; i++) {
            ItemStack stack = bag.getItem(i);
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /**
     * Armour points from the worn gear's flat {@code ARMOR} modifiers (item defaults, the
     * attribute-modifier component — where Adventure Item Stats bonuses live — and enchantments).
     * Rounded down, as the HUD's armour bar draws it.
     */
    private static int armour(PlayerMobEntity ghost) {
        double[] total = {0.0};
        for (EquipmentSlot slot : ARMOUR_SLOTS) {
            ghost.getItemBySlot(slot).forEachModifier(slot, (attribute, modifier) -> {
                if (attribute.is(Attributes.ARMOR) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                    total[0] += modifier.amount();
                }
            });
        }
        return (int) Math.floor(total[0]);
    }
}
