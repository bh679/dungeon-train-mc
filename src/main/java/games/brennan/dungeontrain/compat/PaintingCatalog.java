package games.brennan.dungeontrain.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.PaintingVariantTags;
import net.minecraft.world.entity.decoration.PaintingVariant;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The pictures a painting variant row can choose from: the data-driven
 * {@code painting_variant} registry, narrowed to the {@code #minecraft:placeable}
 * tag (the same pool a vanilla painting item rolls from). Read straight from the
 * caller's {@link RegistryAccess} — the server's at stamp time, the client's for
 * the editor menu (painting variants are a synced registry) — so there is no
 * cache to go stale across worlds or datapack reloads.
 */
public final class PaintingCatalog {

    private PaintingCatalog() {}

    /** One picture: its registry id and its size in blocks. */
    public record Picture(ResourceLocation id, int width, int height) {
        public int area() {
            return width * height;
        }
    }

    /** Every placeable picture, sorted by id so seeded rolls are stable. */
    public static List<Picture> placeable(RegistryAccess access) {
        Optional<? extends Registry<PaintingVariant>> registry = access.registry(Registries.PAINTING_VARIANT);
        if (registry.isEmpty()) return List.of();
        List<Picture> out = new ArrayList<>();
        for (Holder<PaintingVariant> holder : registry.get().getTagOrEmpty(PaintingVariantTags.PLACEABLE)) {
            Picture p = toPicture(holder);
            if (p != null) out.add(p);
        }
        out.sort(Comparator.comparing(p -> p.id().toString()));
        return List.copyOf(out);
    }

    /** Any registered picture by id (placeable or not — an author may pick a non-placeable one on purpose). */
    @Nullable
    public static Picture byId(RegistryAccess access, ResourceLocation id) {
        Optional<? extends Registry<PaintingVariant>> registry = access.registry(Registries.PAINTING_VARIANT);
        if (registry.isEmpty()) return null;
        PaintingVariant v = registry.get().get(id);
        return v == null ? null : new Picture(id, v.width(), v.height());
    }

    /** The distinct sizes among {@code pictures}, smallest area first, as {@code [width, height]} pairs. */
    public static List<int[]> sizes(List<Picture> pictures) {
        List<int[]> out = new ArrayList<>();
        for (Picture p : pictures) {
            boolean seen = false;
            for (int[] s : out) {
                if (s[0] == p.width() && s[1] == p.height()) {
                    seen = true;
                    break;
                }
            }
            if (!seen) out.add(new int[]{p.width(), p.height()});
        }
        out.sort(Comparator.<int[]>comparingInt(s -> s[0] * s[1]).thenComparingInt(s -> s[0]));
        return List.copyOf(out);
    }

    @Nullable
    private static Picture toPicture(Holder<PaintingVariant> holder) {
        Optional<ResourceLocation> id = holder.unwrapKey().map(k -> k.location());
        if (id.isEmpty()) return null;
        PaintingVariant v = holder.value();
        return new Picture(id.get(), v.width(), v.height());
    }
}
