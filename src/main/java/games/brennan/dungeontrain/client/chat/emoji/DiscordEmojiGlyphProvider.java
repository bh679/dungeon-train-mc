package games.brennan.dungeontrain.client.chat.emoji;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.client.gui.font.providers.GlyphProviderType;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * The {@code dungeontrain:discord_emoji} glyph provider: serves each loaded custom Discord emoji
 * ({@link DiscordEmojiCache}) as one coloured character, 8 GUI pixels tall like vanilla text.
 *
 * <p>Registered as its own font-provider type through NeoForge's enum extension of
 * {@link GlyphProviderType} ({@code META-INF/enumextensions.json} → {@link #ENUM_PARAMS}); the font file
 * {@code assets/dungeontrain/font/discord_emoji.json} names it. A FontSet probes every declared character
 * when it (re)loads and drops a provider that answers none — at startup no emoji has loaded yet — so the
 * first slot is a permanent blank ({@link #KEEPALIVE}) that keeps this provider in. Any other unloaded slot
 * answers null, and {@link EmojiText} never writes one.</p>
 */
public final class DiscordEmojiGlyphProvider implements GlyphProvider {

    private static final float ADVANCE = 9.0F;
    private static final float OVERSAMPLE = DiscordEmojiCache.SIZE / 8.0F;
    private static final GlyphInfo KEEPALIVE = (GlyphInfo.SpaceGlyphInfo) () -> 0.0F;

    /** The provider definition the font JSON's {@code "type": "dungeontrain:discord_emoji"} decodes to. */
    public record Definition() implements GlyphProviderDefinition {
        public static final Definition INSTANCE = new Definition();
        public static final MapCodec<Definition> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public GlyphProviderType type() {
            return ENUM_PARAMS.getValue();
        }

        @Override
        public Either<Loader, Reference> unpack() {
            return Either.left(resourceManager -> new DiscordEmojiGlyphProvider());
        }
    }

    /** Constructor arguments for the extended {@link GlyphProviderType} constant (serialized name, codec). */
    public static final EnumProxy<GlyphProviderType> ENUM_PARAMS =
        new EnumProxy<>(GlyphProviderType.class, "dungeontrain:discord_emoji", Definition.CODEC);

    @Nullable
    @Override
    public GlyphInfo getGlyph(int character) {
        if (character == DiscordEmojiCache.KEEPALIVE_SLOT) return KEEPALIVE;
        NativeImage image = DiscordEmojiCache.image(character);
        return image == null ? null : new Glyph(image);
    }

    @Override
    public IntSet getSupportedGlyphs() {
        IntSet all = new IntOpenHashSet(DiscordEmojiCache.MAX_SLOTS);
        for (int i = 0; i < DiscordEmojiCache.MAX_SLOTS; i++) all.add(DiscordEmojiCache.FIRST_SLOT + i);
        return all;
    }

    private record Glyph(NativeImage image) implements GlyphInfo {
        @Override
        public float getAdvance() {
            return ADVANCE;
        }

        @Override
        public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> baker) {
            return baker.apply(new SheetGlyphInfo() {
                @Override
                public int getPixelWidth() {
                    return image.getWidth();
                }

                @Override
                public int getPixelHeight() {
                    return image.getHeight();
                }

                @Override
                public void upload(int x, int y) {
                    image.upload(0, x, y, 0, 0, image.getWidth(), image.getHeight(), false, false);
                }

                @Override
                public boolean isColored() {
                    return true;
                }

                @Override
                public float getOversample() {
                    return OVERSAMPLE;
                }
            });
        }
    }
}
