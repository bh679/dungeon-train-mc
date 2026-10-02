package games.brennan.dungeontrain.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import games.brennan.dungeontrain.compat.PaintingTransformProcessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModStructureProcessors} really lands its types in {@code minecraft:worldgen/structure_processor}:
 * the DeferredRegister is fired against the built-in registry the way NeoForge does at mod load, then
 * each type is looked up and {@link PaintingTransformProcessor} is pushed through the vanilla dispatch
 * codec — the serialisation that threw "unregistered processor type" before it had an id.
 */
final class ModStructureProcessorsTest {

    private static final ResourceLocation PAINTING_TRANSFORM =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "painting_transform");

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void registerProcessors() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<StructureProcessorType<?>> registry =
            (MappedRegistry<StructureProcessorType<?>>) BuiltInRegistries.STRUCTURE_PROCESSOR;
        if (registry.containsKey(PAINTING_TRANSFORM)) return;
        registry.unfreeze();
        try {
            fire(ModStructureProcessors.PROCESSORS, registry);
        } finally {
            registry.freeze();
        }
    }

    /** NeoForge's own path: a {@link RegisterEvent} for the registry, handed to the DeferredRegister. */
    private static void fire(DeferredRegister<?> register, Registry<?> registry) throws ReflectiveOperationException {
        Constructor<RegisterEvent> ctor = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, Registry.class);
        ctor.setAccessible(true);
        RegisterEvent event = ctor.newInstance(Registries.STRUCTURE_PROCESSOR, registry);
        Method addEntries = DeferredRegister.class.getDeclaredMethod("addEntries", RegisterEvent.class);
        addEntries.setAccessible(true);
        addEntries.invoke(register, event);
    }

    @Test
    @DisplayName("painting_transform resolves to PaintingTransformProcessor.TYPE and back")
    void paintingTransformResolves() {
        assertSame(PaintingTransformProcessor.TYPE, BuiltInRegistries.STRUCTURE_PROCESSOR.get(PAINTING_TRANSFORM));
        assertEquals(PAINTING_TRANSFORM, BuiltInRegistries.STRUCTURE_PROCESSOR.getKey(PaintingTransformProcessor.TYPE));
    }

    @Test
    @DisplayName("every DT processor type is registered under its dungeontrain id")
    void everyEntryResolves() {
        for (String name : List.of("lost_city_swap", "lost_city_truncate", "lost_city_bite",
                                   "lost_city_stretch", "lost_city_facade", "lost_city_variants",
                                   "painting_transform")) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dungeontrain", name);
            assertTrue(BuiltInRegistries.STRUCTURE_PROCESSOR.containsKey(id), "missing " + id);
        }
    }

    @Test
    @DisplayName("a painting processor round-trips through the processor dispatch codec")
    void paintingProcessorSerialises() {
        JsonElement json = StructureProcessorType.SINGLE_CODEC
            .encodeStart(JsonOps.INSTANCE, PaintingTransformProcessor.of(true))
            .getOrThrow();
        JsonObject expected = new JsonObject();
        expected.addProperty("processor_type", PAINTING_TRANSFORM.toString());
        assertEquals(expected, json);

        StructureProcessor decoded = StructureProcessorType.SINGLE_CODEC
            .parse(JsonOps.INSTANCE, json)
            .getOrThrow();
        assertInstanceOf(PaintingTransformProcessor.class, decoded);
    }

    @Test
    @DisplayName("the cached painting check matches Fast Paintings' block and never air")
    void cachedPaintingCheck() {
        BuiltInRegistries.BLOCK.getOptional(PaintingTransformProcessor.PAINTING_BLOCK).ifPresent(block ->
            assertTrue(PaintingTransformProcessor.isPaintingBlock(block.defaultBlockState())));
        assertFalse(PaintingTransformProcessor.isPaintingBlock(Blocks.AIR.defaultBlockState()));
        assertFalse(PaintingTransformProcessor.isPaintingBlock(Blocks.STONE.defaultBlockState()));
    }
}
