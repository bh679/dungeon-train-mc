package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No bundled template may ship a pressure plate that is already pressed.
 *
 * <p>A pressed plate gives power to the block under it the moment the template is built, so a trap
 * room lights its own TNT with nobody standing on anything. That is how AshSeven's {@code tntfull}
 * portal room blew up 0.36 s after they walked in (16 Nov 2026): the template had been saved with
 * someone standing on two of its plates. The editor saves whatever state the plates are in, so this
 * scans every shipped template, and every variant state string, for a pressed plate.</p>
 */
class BundledPressurePlateStateTest {

    private static final Path DATA = RepoPaths.resources().resolve("data/dungeontrain");

    /** A variant state string naming a pressed plate, e.g. {@code minecraft:stone_pressure_plate[powered=true]}. */
    private static final Pattern PRESSED_VARIANT =
        Pattern.compile("[a-z0-9_:]*pressure_plate\\[[^\\]]*(powered=true|power=(?!0\\b)\\d+)[^\\]]*\\]");

    @Test
    @DisplayName("no bundled .nbt template has a pressed pressure plate")
    void templatesShipUnpressedPlates() throws IOException {
        List<String> pressed = new ArrayList<>();
        for (Path file : filesEndingWith(".nbt")) {
            pressed.addAll(pressedPlatesIn(file));
        }
        assertTrue(pressed.isEmpty(),
            "Pressed pressure plates light their trap as the template is built. Re-save with the plates up:\n  "
                + String.join("\n  ", pressed));
    }

    @Test
    @DisplayName("no bundled variants file offers a pressed pressure plate")
    void variantsOfferUnpressedPlates() throws IOException {
        List<String> pressed = new ArrayList<>();
        for (Path file : filesEndingWith(".json")) {
            Matcher m = PRESSED_VARIANT.matcher(Files.readString(file));
            while (m.find()) pressed.add(DATA.relativize(file) + ": " + m.group());
        }
        assertTrue(pressed.isEmpty(), "Pressed pressure plates in variant states:\n  " + String.join("\n  ", pressed));
    }

    @Test
    @DisplayName("the pattern catches pressed plates and ignores unpressed ones")
    void variantPatternMatchesOnlyPressedPlates() {
        assertTrue(PRESSED_VARIANT.matcher("minecraft:stone_pressure_plate[powered=true]").find());
        assertTrue(PRESSED_VARIANT.matcher("minecraft:light_weighted_pressure_plate[power=7]").find());
        assertTrue(!PRESSED_VARIANT.matcher("minecraft:stone_pressure_plate[powered=false]").find());
        assertTrue(!PRESSED_VARIANT.matcher("minecraft:heavy_weighted_pressure_plate[power=0]").find());
    }

    private static List<Path> filesEndingWith(String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(DATA)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .sorted()
                .toList();
        }
    }

    /** Every pressed plate in one structure template, as "file (x,y,z) block". Non-structure NBT yields none. */
    private static List<String> pressedPlatesIn(Path file) throws IOException {
        CompoundTag root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            CompoundTag state = palette.getCompound(block.getInt("state"));
            if (isPressedPlate(state)) {
                ListTag pos = block.getList("pos", Tag.TAG_INT);
                out.add(DATA.relativize(file) + " (" + pos.getInt(0) + "," + pos.getInt(1) + "," + pos.getInt(2)
                    + ") " + state.getString("Name"));
            }
        }
        return out;
    }

    private static boolean isPressedPlate(CompoundTag state) {
        if (!state.getString("Name").endsWith("pressure_plate")) return false;
        CompoundTag props = state.getCompound("Properties");
        if ("true".equals(props.getString("powered"))) return true;
        String power = props.getString("power");
        return !power.isEmpty() && !"0".equals(power);
    }
}
