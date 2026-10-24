package games.brennan.dungeontrain.perks;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Shared helper for attribute-based perks: one permanent modifier per (perk, attribute), keyed
 * by {@code dungeontrain:perk/<id>}, replaced whenever the rank changes and removed at rank 0.
 * Permanent modifiers persist with the entity, so {@link PerkEffect#onPlayerReady} re-applying
 * them is idempotent (the old one is removed first).
 */
public final class PerkAttributeModifiers {

    private PerkAttributeModifiers() {}

    public static ResourceLocation idFor(String perkId) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk/" + perkId);
    }

    /**
     * Set the perk's modifier on {@code attribute} to {@code amount}; {@code amount == 0} removes
     * it. Returns true when the attribute exists on the entity.
     */
    public static boolean apply(LivingEntity entity, Holder<Attribute> attribute, String perkId,
                                double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) return false;
        ResourceLocation id = idFor(perkId);
        instance.removeModifier(id);
        if (amount != 0.0) {
            instance.addPermanentModifier(new AttributeModifier(id, amount, operation));
        }
        return true;
    }

    public static void remove(LivingEntity entity, Holder<Attribute> attribute, String perkId) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) instance.removeModifier(idFor(perkId));
    }
}
