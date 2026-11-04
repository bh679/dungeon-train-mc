package games.brennan.dungeontrain.cheat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Customizable Player Models is on the whitelist for its looks — but it also carries a server-side
 * scaler that can change a player's size, hitbox, health, reach, damage, speed, jump, step height,
 * fall damage and how far mobs see them. Every one of those ships OFF, yet in singleplayer the
 * "server" is the player's own machine: its world file ({@code data/cpm.json}), its defaults file or
 * {@code /cpm scaling} can switch them on.
 *
 * <p>Rather than read three config surfaces, watch the result: CPM applies every scale as an
 * attribute modifier under one id ({@link #CPM_MODIFIER_ID}, from its {@code AttributeScaler}), added
 * only while a scale differs from 1. A player carrying it is playing a different game, so the run is
 * Free Play ({@link RunIntegrity#markCheated}, permanent like a cheat command). Checked every
 * {@link #CHECK_TICKS} ticks per player; nothing runs without CPM installed.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CpmScalingIntegrity {

    public static final String MOD_ID = "cpm";

    /** The id CPM 0.6.x gives every scaling modifier ({@code com.tom.cpm.common.AttributeScaler.CPM_ATTR_ID}). */
    public static final ResourceLocation CPM_MODIFIER_ID =
        ResourceLocation.fromNamespaceAndPath("cpm", "24bba381-9615-4530-8fcf-4fc42393a4b5");

    static final int CHECK_TICKS = 40;

    private static volatile Boolean installed = null;

    private CpmScalingIntegrity() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % CHECK_TICKS != 0 || !isInstalled()) return;
        if (RunIntegrity.isPermanentlyCheated(player)) return;
        if (!isScaled(player)) return;
        RunIntegrity.markCheated(player, Component.translatable("chat.dungeontrain.free_play.cause.cpm_scaling"));
    }

    /** Does any of the player's attributes carry a CPM scaling modifier that changes it? */
    static boolean isScaled(ServerPlayer player) {
        for (Holder<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.asHolderIdMap()) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) continue;
            AttributeModifier modifier = instance.getModifier(CPM_MODIFIER_ID);
            if (modifier != null && changes(modifier.id(), modifier.amount())) return true;
        }
        return false;
    }

    /** Pure: is this a CPM scaling modifier that moves the value at all? Package-visible for tests. */
    static boolean changes(ResourceLocation id, double amount) {
        return CPM_MODIFIER_ID.equals(id) && Math.abs(amount) > 1.0e-6;
    }

    private static boolean isInstalled() {
        Boolean known = installed;
        if (known != null) return known;
        boolean loaded;
        try {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(MOD_ID);
        } catch (Throwable t) {
            loaded = false;
        }
        installed = loaded;
        return loaded;
    }
}
