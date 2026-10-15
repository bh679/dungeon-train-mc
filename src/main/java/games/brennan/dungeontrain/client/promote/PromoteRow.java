package games.brennan.dungeontrain.client.promote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.localization.edit.ModJarResources;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One advancement on the promote screen, named and iconned without a world loaded: the title from
 * its lang key, the icon from DT's own advancement JSON in the mod jar (other mods' and vanilla
 * advancements show the title alone). An id with no translation shows as the raw id.
 */
@OnlyIn(Dist.CLIENT)
record PromoteRow(ResourceLocation id, Component title, ItemStack icon) {

    private static final String ADVANCEMENT_ROOT = "data/" + DungeonTrain.MOD_ID + "/advancement/";

    static PromoteRow of(ResourceLocation id) {
        return new PromoteRow(id, titleOf(id), iconOf(id));
    }

    /** {@code advancements.<ns>.<path>.title}; vanilla's own drop the namespace. */
    static Component titleOf(ResourceLocation id) {
        String path = id.getPath().replace('/', '.');
        String namespaced = "advancements." + id.getNamespace() + "." + path + ".title";
        if (I18n.exists(namespaced)) return Component.translatable(namespaced);
        String bare = "advancements." + path + ".title";
        if (ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace()) && I18n.exists(bare)) {
            return Component.translatable(bare);
        }
        return Component.literal(id.toString());
    }

    private static ItemStack iconOf(ResourceLocation id) {
        if (!DungeonTrain.MOD_ID.equals(id.getNamespace())) return null;
        try {
            String json = ModJarResources.read(ADVANCEMENT_ROOT + id.getPath() + ".json");
            if (json == null) return null;
            JsonObject display = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("display");
            if (display == null || !display.has("icon")) return null;
            ResourceLocation item = ResourceLocation.tryParse(
                display.getAsJsonObject("icon").get("id").getAsString());
            if (item == null) return null;
            var found = BuiltInRegistries.ITEM.get(item);
            return found == Items.AIR ? null : new ItemStack(found);
        } catch (RuntimeException e) {
            return null; // a generated or odd-shaped advancement simply has no icon here
        }
    }

    /** "Hard", "Easy", … in the player's language. */
    static Component difficultyName(String key) {
        Difficulty d = Difficulty.byName(key);
        return d == null ? Component.literal(key) : d.getDisplayName();
    }
}
