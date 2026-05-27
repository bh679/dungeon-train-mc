package games.brennan.dungeontrain.registry;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.PlayerRunState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Mod-side AttachmentType registry. Wires {@link PlayerRunState} onto
 * {@link net.minecraft.world.entity.player.Player} so per-player streak
 * state survives world save/reload.
 *
 * <p>Registered from {@link DungeonTrain}'s constructor on the mod-event
 * bus, mirroring {@link ModItems#register(IEventBus)}.</p>
 */
public final class ModDataAttachments {

    public static final DeferredRegister<AttachmentType<?>> TYPES =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, DungeonTrain.MOD_ID);

    /**
     * Per-player run state. Codec-serialized so the streak persists across
     * world reload; the {@code AchievementEvents} respawn hook explicitly
     * clears it on player death.
     */
    public static final Supplier<AttachmentType<PlayerRunState>> PLAYER_RUN_STATE =
        TYPES.register("player_run_state",
            () -> AttachmentType.builder(PlayerRunState::new)
                .serialize(PlayerRunState.CODEC)
                .build()
        );

    private ModDataAttachments() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
