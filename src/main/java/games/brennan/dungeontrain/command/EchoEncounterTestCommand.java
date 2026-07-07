package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.echo.RemoteEchoEncounters;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dev-only command to exercise the remote-echo {@link RemoteEchoEncounters encounter journal}
 * without a relay or a seeded remote pool — registered only outside production by
 * {@code event.CommandEvents}.
 *
 * <ul>
 *   <li>{@code /dtechotest summon [name] [relayId]} — spawn a PlayerMob in front of you and open a
 *       remote-echo journal for it (as if it had spawned as a remote echo of {@code name}). Interact
 *       with it — approach, crouch, hit it / let it hit you, trade, shove it off the deck, kill it —
 *       then the story posts when it dies, you die, or you run {@code finish}. With {@code relayId}
 *       the record poses as a relay-imported life ({@code discordpresence:<relayId>}), so
 *       {@link games.brennan.dungeontrain.echo.EchoUsageReporter} fires a real
 *       {@code /reincarnations/used} report for that id — the dev path for verifying echo-usage
 *       counting end-to-end.</li>
 *   <li>{@code /dtechotest finish} — end every open journal now and post its story.</li>
 * </ul>
 *
 * <p>This drives the DT side directly; the real spawn seam ({@code PlayerMobSpawnHooks}) is covered by
 * the in-game relay path. The hard references to PlayerMob types are safe — this class is only
 * registered when {@code playermob} is loaded (a dev environment always has the bundled jar).</p>
 */
public final class EchoEncounterTestCommand {

    private static final ResourceLocation PLAYER_MOB_ID =
        ResourceLocation.fromNamespaceAndPath("playermob", "player_mob");
    private static final int TEST_CARRIAGE = 5;

    private EchoEncounterTestCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dtechotest")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("summon")
                .executes(ctx -> summon(ctx, "TestSoul", 0))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name"), 0))
                    .then(Commands.argument("relayId", IntegerArgumentType.integer(1))
                        .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name"),
                            IntegerArgumentType.getInteger(ctx, "relayId"))))))
            .then(Commands.literal("upgrade")
                .executes(EchoEncounterTestCommand::upgrade))
            .then(Commands.literal("finish")
                .executes(EchoEncounterTestCommand::finish)));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name, int relayId) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        if (!DungeonTrainConfig.isEchoEncounterToDiscord()) {
            source.sendFailure(Component.literal(
                "echoEncounterToDiscord is OFF in config — the journal won't open. Enable it first."));
            return 0;
        }
        ServerLevel level = source.getLevel();

        Optional<EntityType<?>> typeOpt = EntityType.byString(PLAYER_MOB_ID.toString());
        if (typeOpt.isEmpty()) {
            source.sendFailure(Component.literal("PlayerMob entity not registered — is the bundled mod present?"));
            return 0;
        }
        Entity entity = typeOpt.get().create(level);
        if (!(entity instanceof PlayerMobEntity mob)) {
            if (entity != null) entity.discard();
            source.sendFailure(Component.literal("Failed to create a PlayerMob."));
            return 0;
        }

        mob.setUUID(UUID.randomUUID());
        Vec3 look = player.getLookAngle();
        Vec3 pos = player.position().add(look.x * 3.0, 0.0, look.z * 3.0);
        mob.moveTo(pos.x, pos.y, pos.z, player.getYRot() + 180.0f, 0.0f);
        // COMMAND (not EVENT) so PlayerMob rolls a normal skin/personality but does NOT attempt its
        // own reincarnation — we open the journal ourselves below.
        try {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)),
                MobSpawnType.COMMAND, null);
        } catch (Throwable ignored) {
            // Defaults are fine for a test mob.
        }
        mob.setCustomName(Component.literal("Echo of " + name));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        if (!level.addFreshEntity(mob)) {
            source.sendFailure(Component.literal("Level rejected the test mob."));
            return 0;
        }

        // Gear the test echo (enchanted weapon, trimmed enchanted armour, a rare backpack item) so the
        // story's "best items" line — captured below in onRemoteEchoSpawned — has something to describe.
        gearUp(mob, level.registryAccess());

        // With a relayId the record poses as a relay-imported life, so the usage reporter fires a
        // real /reincarnations/used POST for it (dev relay on dev builds); without one, "dttest"
        // keeps the reporter silent — a synthetic life never touched the relay's pool.
        ReincarnationRecord record = relayId > 0
            ? new ReincarnationRecord(
                "discordpresence", String.valueOf(relayId), UUID.randomUUID(), name,
                TEST_CARRIAGE, "", new CompoundTag(), List.of())
            : new ReincarnationRecord(
                "dttest", UUID.randomUUID().toString(), UUID.randomUUID(), name,
                TEST_CARRIAGE, "", new CompoundTag(), List.of());
        RemoteEchoEncounters.onRemoteEchoSpawned(mob, record);

        source.sendSuccess(() -> Component.literal(
                "[echotest] summoned remote echo of '" + name + "' and opened a journal ("
                    + RemoteEchoEncounters.activeCount() + " active). Interact, then kill it or /dtechotest finish.")
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /**
     * Dress the test echo in representative gear so {@code EchoItemHighlights} has notable items to
     * surface: an enchanted netherite sword (highest score), a trimmed, enchanted diamond chestplate,
     * and an enchanted golden apple in the backpack (a rare non-gear item). Best-effort — any registry
     * miss leaves the echo bare rather than failing the command.
     */
    private static void gearUp(PlayerMobEntity mob, RegistryAccess registries) {
        try {
            ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
            sword.enchant(enchantment(registries, Enchantments.SHARPNESS), 5);
            sword.enchant(enchantment(registries, Enchantments.UNBREAKING), 3);
            mob.setItemSlot(EquipmentSlot.MAINHAND, sword);

            ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
            chest.enchant(enchantment(registries, Enchantments.PROTECTION), 4);
            Holder<TrimMaterial> material = registries.lookupOrThrow(Registries.TRIM_MATERIAL)
                    .getOrThrow(TrimMaterials.NETHERITE);
            Holder<TrimPattern> pattern = registries.lookupOrThrow(Registries.TRIM_PATTERN)
                    .getOrThrow(TrimPatterns.SILENCE);
            chest.set(DataComponents.TRIM, new ArmorTrim(material, pattern));
            // Buff its armour above the vanilla default so the story prints a (non-default) stat —
            // the plain sword/axe stay standard and correctly show no stat.
            ItemAttributeModifiers buffed = chest.getItem().getDefaultAttributeModifiers()
                    .withModifierAdded(Attributes.ARMOR,
                            new AttributeModifier(
                                    ResourceLocation.fromNamespaceAndPath("dungeontrain", "echo_test_armor"),
                                    4.0, AttributeModifier.Operation.ADD_VALUE),
                            EquipmentSlotGroup.CHEST);
            chest.set(DataComponents.ATTRIBUTE_MODIFIERS, buffed);
            mob.setItemSlot(EquipmentSlot.CHEST, chest);

            mob.getInventory().setItem(0, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
        } catch (Throwable ignored) {
            // Leave the echo bare — the story simply omits the gear line.
        }
    }

    private static Holder<Enchantment> enchantment(RegistryAccess registries,
                                                   net.minecraft.resources.ResourceKey<Enchantment> key) {
        return registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /**
     * Dev-only: hand the nearest test echo a strictly-better weapon (an enchanted netherite axe,
     * scoring above the summon's sword) so the next encounter scan logs an "Along the way it
     * claimed …" upgrade beat. One upgrade per echo — a repeat call re-gives the same item, which is
     * already named and so won't re-log.
     */
    private static int upgrade(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        PlayerMobEntity echo = level.getEntitiesOfClass(PlayerMobEntity.class,
                        player.getBoundingBox().inflate(16.0)).stream()
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (echo == null) {
            source.sendFailure(Component.literal("No PlayerMob within 16 blocks — summon one first."));
            return 0;
        }
        try {
            ItemStack axe = new ItemStack(Items.NETHERITE_AXE);
            axe.enchant(enchantment(level.registryAccess(), Enchantments.SHARPNESS), 5);
            axe.enchant(enchantment(level.registryAccess(), Enchantments.UNBREAKING), 3);
            echo.setItemSlot(EquipmentSlot.MAINHAND, axe);
        } catch (Throwable t) {
            source.sendFailure(Component.literal("Failed to gear the echo: " + t));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[echotest] gave the nearest echo a Netherite Axe (Sharpness V) — the next scan tick "
                    + "should log it as a claimed upgrade.").withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int finish(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int ended = RemoteEchoEncounters.devEndAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "[echotest] ended " + ended + " open journal(s) — each story posts once its screenshot lands "
                    + "(or after a short wait), if Discord is configured.")
            .withStyle(ChatFormatting.AQUA), false);
        return ended;
    }
}
