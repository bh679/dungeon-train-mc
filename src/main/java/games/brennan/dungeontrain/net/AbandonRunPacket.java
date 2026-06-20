package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.List;

/**
 * Client → server: the player chose "Abandon This Run" on the pause menu and
 * wants to end the current run immediately.
 *
 * <p>Empty payload — the server identifies the sender via
 * {@link IPayloadContext#player()} and kills them with a randomly-picked
 * Dungeon-Train flavored damage source ({@link #ABANDON_CAUSES}) instead of the
 * generic kill source, so the death-screen fall-page title and the Discord death
 * report read e.g. "You stepped off the train" / "Brennan stepped off the train"
 * rather than the generic "died". That fires {@code LivingDeathEvent}, so
 * {@link games.brennan.dungeontrain.event.RunStatsEvents} sends the
 * {@link DeathStatsPacket} (whose {@code deathCause} is derived from this very
 * source) and the client opens the death screen —
 * {@link games.brennan.dungeontrain.client.DeathScreenLayoutHandler} swaps it
 * for the narrative recap, exactly like a normal in-run death.</p>
 *
 * <p>Each abandon damage type is tagged into {@code minecraft:bypasses_invulnerability},
 * {@code bypasses_armor}, and {@code bypasses_effects} (mirroring {@code genericKill}),
 * so the kill also ends a creative / Free Play run regardless of armor or effects.
 * Being a {@code BYPASSES_INVULNERABILITY} source, it is also excluded from the
 * run's damage-taken stat by {@code RunStatsEvents#onLivingDamage}. The client
 * closes the pause screen <em>before</em> sending (see {@code PauseMenuLayoutHandler})
 * so the integrated server is unpaused and can process the kill.</p>
 */
public record AbandonRunPacket() implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The pool of Dungeon-Train flavored death causes; one is picked at random
     * each time a run is abandoned. Each key resolves to a datapack
     * {@code damage_type} (under {@code data/dungeontrain/damage_type/}) whose
     * {@code message_id} maps to a {@code death.attack.dungeontrain.*} lang key.
     */
    private static final List<ResourceKey<DamageType>> ABANDON_CAUSES = List.of(
        causeKey("abandoned"), causeKey("stepped_off"),
        causeKey("left_the_line"), causeKey("walked_away"));

    public static final Type<AbandonRunPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "abandon_run"));

    public static final StreamCodec<FriendlyByteBuf, AbandonRunPacket> STREAM_CODEC =
        StreamCodec.unit(new AbandonRunPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AbandonRunPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.isDeadOrDying()) return;
            LOGGER.info("[DungeonTrain] {} abandoned the run from the pause menu", player.getGameProfile().getName());
            ServerLevel level = player.serverLevel();
            ResourceKey<DamageType> picked =
                ABANDON_CAUSES.get(level.getRandom().nextInt(ABANDON_CAUSES.size()));
            Holder<DamageType> holder = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(picked);
            // Float.MAX_VALUE + the bypass tags on each abandon damage type
            // guarantee the kill regardless of armor / effects / creative invuln.
            player.hurt(new DamageSource(holder), Float.MAX_VALUE);
        });
    }

    private static ResourceKey<DamageType> causeKey(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, path));
    }
}
