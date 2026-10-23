package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.compat.photo.PhotoSubjectTally.Subjects;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.npc.Villager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-game photo tally and the generated mob photo collections agree on what an animal is: every
 * entity in Nature Documentary counts toward the wildlife tally, and every one in Most Wanted counts
 * as hostile and never as an animal. Classes come from the code, not from a mob category, so this
 * checks the same {@link PhotoSubjectTally#classifyType} the shot runs through.
 */
class PhotoSubjectTallyCollectionsTest {

    /** Entities not in vanilla 1.21.1's {@code EntityType} fields, by class name (loaded without init). */
    private static final Map<String, String> MODDED = Map.ofEntries(
            Map.entry("minecraft:creaking", "com.blackgear.vanillabackport.common.level.entities.creaking.Creaking"),
            Map.entry("betterend:end_slime", "org.betterx.betterend.entity.EndSlimeEntity"),
            Map.entry("betterend:shadow_walker", "org.betterx.betterend.entity.ShadowWalkerEntity"),
            Map.entry("betternether:firefly", "org.betterx.betternether.entity.EntityFirefly"),
            Map.entry("betternether:flying_pig", "org.betterx.betternether.entity.EntityFlyingPig"),
            Map.entry("betternether:jungle_skeleton", "org.betterx.betternether.entity.EntityJungleSkeleton"),
            Map.entry("betternether:naga", "org.betterx.betternether.entity.EntityNaga"),
            Map.entry("betternether:skull", "org.betterx.betternether.entity.EntitySkull"),
            Map.entry("deeperdarker:anger_pot", "com.kyanite.deeperdarker.content.entities.OvercastPot"),
            Map.entry("deeperdarker:fear_pot", "com.kyanite.deeperdarker.content.entities.OvercastPot"),
            Map.entry("deeperdarker:sorrow_pot", "com.kyanite.deeperdarker.content.entities.OvercastPot"),
            Map.entry("deeperdarker:sculk_centipede", "com.kyanite.deeperdarker.content.entities.SculkCentipede"),
            Map.entry("deeperdarker:sculk_leech", "com.kyanite.deeperdarker.content.entities.SculkLeech"),
            Map.entry("deeperdarker:sculk_snapper", "com.kyanite.deeperdarker.content.entities.SculkSnapper"),
            Map.entry("deeperdarker:shattered", "com.kyanite.deeperdarker.content.entities.Shattered"),
            Map.entry("deeperdarker:shriek_worm", "com.kyanite.deeperdarker.content.entities.ShriekWorm"),
            Map.entry("deeperdarker:sludge", "com.kyanite.deeperdarker.content.entities.Sludge"),
            Map.entry("deeperdarker:stalker", "com.kyanite.deeperdarker.content.entities.Stalker"));

    private static final Map<String, Class<?>> VANILLA = new HashMap<>();

    @BeforeAll
    static void bootstrap() throws IllegalAccessException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // EntityType.SNOW_GOLEM is an EntityType<SnowGolem>: the field's type argument is the class.
        for (Field f : EntityType.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != EntityType.class) continue;
            if (!(f.getGenericType() instanceof ParameterizedType p)) continue;
            if (!(p.getActualTypeArguments()[0] instanceof Class<?> c)) continue;
            VANILLA.put(BuiltInRegistries.ENTITY_TYPE.getKey((EntityType<?>) f.get(null)).toString(), c);
        }
    }

    private static Class<?> classOf(String id) throws ClassNotFoundException {
        String name = MODDED.get(id);
        if (name != null) return Class.forName(name, false, PhotoSubjectTallyCollectionsTest.class.getClassLoader());
        Class<?> c = VANILLA.get(id);
        if (c == null) throw new AssertionError(id + " has no entity class: add it to MODDED");
        return c;
    }

    private static List<String> criteria(String file) throws IOException {
        JsonObject adv = JsonParser.parseString(Files.readString(RepoPaths.advancements().resolve(file))).getAsJsonObject();
        List<String> ids = new ArrayList<>();
        for (String key : adv.getAsJsonObject("criteria").keySet()) {
            if (key.contains(":") && !key.startsWith("dungeontrain:")) ids.add(key);
        }
        assertTrue(ids.size() > 10, file + " has too few entity criteria: " + ids);
        return ids;
    }

    @Test
    @DisplayName("golems and allays are animals, villagers are passengers")
    void spotChecks() {
        Subjects animal = new Subjects(false, false, true);
        assertEquals(animal, PhotoSubjectTally.classifyType(SnowGolem.class, false));
        assertEquals(animal, PhotoSubjectTally.classifyType(IronGolem.class, false));
        assertEquals(animal, PhotoSubjectTally.classifyType(Allay.class, false));
        assertEquals(new Subjects(true, false, false), PhotoSubjectTally.classifyType(Villager.class, false));
    }

    @Test
    @DisplayName("every Nature Documentary entity counts as an animal")
    void natureDocumentaryAreAnimals() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String id : criteria("enchiridion/nature_documentary.json")) {
            Subjects s = PhotoSubjectTally.classifyType(classOf(id), false);
            if (!s.equals(new Subjects(false, false, true))) wrong.add(id + " -> " + s);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    @DisplayName("every Most Wanted entity counts as hostile, never as an animal")
    void mostWantedAreHostile() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String id : criteria("enchiridion/most_wanted.json")) {
            Subjects s = PhotoSubjectTally.classifyType(classOf(id), false);
            if (!s.equals(new Subjects(false, true, false))) wrong.add(id + " -> " + s);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }
}
