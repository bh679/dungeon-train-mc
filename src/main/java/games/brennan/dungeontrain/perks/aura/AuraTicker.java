package games.brennan.dungeontrain.perks.aura;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.PerkEffect;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Optional;

/**
 * Runs every aura once a second: rolls a player's draws forward to their rank's shape, then gives each
 * switched-on effect to the targets within its range. An application lasts {@link AuraDef#durationTicks}
 * and is topped up only once it is down to {@link AuraDef#topUpTicks}, so a timer-driven effect
 * (Regeneration's heal beat, Absorption's refill) runs at its normal pace rather than restarting each
 * second. A target already holding a stronger or longer copy keeps it.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AuraTicker {

    private static final int PERIOD = 20;

    /** Resends the aura state when a player is a fresh entity (login, respawn, dimension change). */
    public static final PerkEffect READY = new PerkEffect() {
        @Override
        public void onPlayerReady(ServerPlayer player) {
            sync(player);
        }
    };

    private AuraTicker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % PERIOD != 0) return;
        List<AuraDef> defs = List.copyOf(Auras.all());
        if (defs.isEmpty()) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            for (AuraDef def : defs) tick(player, def);
        }
    }

    private static void tick(ServerPlayer player, AuraDef def) {
        int rank = Perks.rank(player, def.perkId());
        if (rank <= 0) return;
        AuraLadder.Shape shape = def.ladder().shape(rank);
        if (shape.count() == 0) return;
        AuraDraws draws = ensureDraws(player, def, shape);
        var off = player.getData(ModDataAttachments.PERK_AURA_TOGGLES.get()).off(def.perkId());

        int count = Math.min(shape.count(), draws.effects().size());
        int maxRange = 0;
        for (int i = 0; i < count; i++) maxRange = Math.max(maxRange, shape.range(i));
        if (maxRange <= 0) return;
        List<LivingEntity> near = player.level().getEntitiesOfClass(LivingEntity.class,
            player.getBoundingBox().inflate(maxRange), e -> def.targets().test(player, e));
        if (near.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            ResourceLocation id = draws.effects().get(i);
            if (off.contains(id)) continue;
            Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceKey.create(Registries.MOB_EFFECT, id));
            if (effect.isEmpty()) continue;
            int amplifier = draws.level(i, shape.levelUps()) - 1;
            double range = shape.range(i);
            for (LivingEntity target : near) {
                if (target.distanceToSqr(player) > range * range) continue;
                give(def, target, effect.get(), amplifier);
            }
        }
    }

    private static void give(AuraDef def, LivingEntity target, Holder<MobEffect> effect, int amplifier) {
        MobEffectInstance current = target.getEffect(effect);
        if (!needsTopUp(current == null ? -1 : current.getAmplifier(), current == null ? 0 : current.getDuration(),
                current != null && current.isInfiniteDuration(), amplifier, def.topUpTicks())) return;
        target.addEffect(new MobEffectInstance(effect, def.durationTicks(), amplifier, true, def.particles(), true));
    }

    /** True when an aura copy at {@code amplifier} should be given over what the target holds. */
    static boolean needsTopUp(int heldAmplifier, int heldTicks, boolean heldInfinite, int amplifier, int topUpTicks) {
        if (heldAmplifier < 0) return true;
        if (heldAmplifier != amplifier) return heldAmplifier < amplifier;
        return !heldInfinite && heldTicks <= topUpTicks;
    }

    /** Roll the player's draws forward to {@code shape}; stores and syncs only when something new was rolled. */
    private static AuraDraws ensureDraws(ServerPlayer player, AuraDef def, AuraLadder.Shape shape) {
        AuraState.Draws all = player.getData(ModDataAttachments.PERK_AURA_DRAWS.get());
        AuraDraws before = all.of(def.perkId());
        var off = player.getData(ModDataAttachments.PERK_AURA_TOGGLES.get()).off(def.perkId());
        AuraDraws after = AuraRoller.extend(def, before, shape, off, bound -> player.getRandom().nextInt(bound));
        if (!after.equals(before)) {
            player.setData(ModDataAttachments.PERK_AURA_DRAWS.get(), all.with(def.perkId(), after));
            sync(player);
        }
        return after;
    }

    /** A switched-on/off request from the player's menu. Only an effect they have drawn can flip. */
    public static void toggle(ServerPlayer player, String auraId, ResourceLocation effect) {
        if (Auras.of(auraId) == null) return;
        if (!player.getData(ModDataAttachments.PERK_AURA_DRAWS.get()).of(auraId).effects().contains(effect)) return;
        AuraState.Toggles toggles = player.getData(ModDataAttachments.PERK_AURA_TOGGLES.get());
        player.setData(ModDataAttachments.PERK_AURA_TOGGLES.get(), toggles.flipped(auraId, effect));
        sync(player);
    }

    public static void sync(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, new AuraSyncPacket(
            player.getData(ModDataAttachments.PERK_AURA_DRAWS.get()),
            player.getData(ModDataAttachments.PERK_AURA_TOGGLES.get())));
    }
}
