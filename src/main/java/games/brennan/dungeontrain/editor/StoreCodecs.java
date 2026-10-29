package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/** The two codecs every {@link ThreeTierStore} in the editor uses. */
public final class StoreCodecs {

    private static final Logger LOGGER = LogUtils.getLogger();

    private StoreCodecs() {}

    /**
     * A JSON-object sidecar: parsed with Gson, written pretty-printed. A file whose root is not an
     * object is logged and treated as absent, as every sidecar store did.
     */
    public static <V> StoreCodec<V> json(Function<JsonObject, V> fromJson, Function<V, JsonElement> toJson) {
        return new StoreCodec<>() {
            @Override
            public V decode(InputStream in, String what, DecodeContext ctx) throws IOException {
                JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (!root.isJsonObject()) {
                    LOGGER.warn("[DungeonTrain] {} is not a JSON object — ignoring", what);
                    return null;
                }
                return fromJson.apply(root.getAsJsonObject());
            }

            @Override
            public void encode(V value, Path file) throws IOException {
                String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(toJson.apply(value));
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    w.write(pretty);
                }
            }
        };
    }

    /**
     * A compressed-NBT {@link StructureTemplate}, run through {@link DoubleBlockTemplateRepair} on
     * read and, when {@code repairOnSave}, on write too (the carriage and part stores do; the file on
     * disk is then the repaired copy). Needs {@link StoreCodec.DecodeContext#blocks()}.
     */
    public static StoreCodec<StructureTemplate> structureTemplate(boolean repairOnSave) {
        return new StoreCodec<>() {
            @Override
            public StructureTemplate decode(InputStream in, String what, DecodeContext ctx) throws IOException {
                CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                StructureTemplate template = new StructureTemplate();
                template.load(ctx.blocks(), DoubleBlockTemplateRepair.repair(tag, what));
                return template;
            }

            @Override
            public void encode(StructureTemplate template, Path file) throws IOException {
                CompoundTag tag = template.save(new CompoundTag());
                if (repairOnSave) tag = DoubleBlockTemplateRepair.repair(tag, "save");
                NbtIo.writeCompressed(tag, file);
            }
        };
    }
}
