package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
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
 *   <li>{@code /dtechotest summon [name]} — spawn a PlayerMob in front of you and open a remote-echo
 *       journal for it (as if it had spawned as a remote echo of {@code name}). Interact with it —
 *       approach, crouch, hit it / let it hit you, trade, shove it off the deck, kill it — then the
 *       story posts when it dies, you die, or you run {@code finish}.</li>
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
                .executes(ctx -> summon(ctx, "TestSoul"))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("finish")
                .executes(EchoEncounterTestCommand::finish)));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name) {
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

        ReincarnationRecord record = new ReincarnationRecord(
            "dttest", UUID.randomUUID().toString(), UUID.randomUUID(), name,
            TEST_CARRIAGE, "", new CompoundTag(), List.of());
        RemoteEchoEncounters.onRemoteEchoSpawned(mob, record);

        source.sendSuccess(() -> Component.literal(
                "[echotest] summoned remote echo of '" + name + "' and opened a journal ("
                    + RemoteEchoEncounters.activeCount() + " active). Interact, then kill it or /dtechotest finish.")
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int finish(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int ended = RemoteEchoEncounters.devEndAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "[echotest] ended " + ended + " open journal(s) — stories posted (if Discord is configured).")
            .withStyle(ChatFormatting.AQUA), false);
        return ended;
    }
}
