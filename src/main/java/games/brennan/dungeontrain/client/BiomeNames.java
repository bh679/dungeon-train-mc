package games.brennan.dungeontrain.client;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A biome's name as players read it, for every screen that shows one ({@link BiomeChecklistScreen}, the photo
 * albums' captions in {@link EarnedPhotoAlbumScreen}). Not every mod names all its biomes (BetterEnd ships no key
 * for two of them), and a bare {@link Component#translatable} would print the key itself, so a biome no language
 * names reads as its id, tidied. {@code BiomeNamesTest} pins that every biome the bands can carry has a key.
 */
public final class BiomeNames {

    private BiomeNames() {}

    /** The game's own name for a biome; its id, tidied, when no language names it ({@code waterfall_ponds} → Waterfall Ponds). */
    public static Component nameOf(ResourceLocation biome) {
        String key = key(biome);
        if (Language.getInstance().has(key)) return Component.translatable(key);
        return Component.literal(tidy(biome.getPath()));
    }

    /** The lang key vanilla names a biome by: {@code biome.<namespace>.<path>}, slashes as dots. */
    public static String key(ResourceLocation biome) {
        return "biome." + biome.getNamespace() + "." + biome.getPath().replace('/', '.');
    }

    /** The last segment of a biome's path, words capitalised: {@code cave/flower_islets} → Flower Islets. */
    static String tidy(String path) {
        StringBuilder name = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }
}
