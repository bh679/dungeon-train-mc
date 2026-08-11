package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a template's photo goes.
 *
 * <p>The store-backed lookups need a loaded install, so what's pinned here is the part that's pure
 * and the part that's easy to get wrong: turning a template path into its sibling {@code .png}.</p>
 */
final class BuilderPhotoPathsTest {

    @Test
    @DisplayName("The photo sits beside the template under the same name")
    void siblingPng() {
        assertEquals(Path.of("templates", "crate_car.png"),
                BuilderPhotoPaths.withPng(Path.of("templates", "crate_car.nbt")));
    }

    @Test
    @DisplayName("A dot in a parent directory isn't mistaken for the extension")
    void dotsInParentDirectoriesAreSafe() {
        // A package working directory named after a version is exactly this shape, and a naive
        // lastIndexOf('.') over the whole path would land in the directory name and produce a file
        // outside the template directory entirely.
        Path in = Path.of("packages", "my.pack.1.2", "templates", "crate_car.nbt");
        assertEquals(Path.of("packages", "my.pack.1.2", "templates", "crate_car.png"),
                BuilderPhotoPaths.withPng(in));
    }

    @Test
    @DisplayName("A dot inside the name keeps everything before the last one")
    void dotsInTheNameKeepTheStem() {
        assertEquals(Path.of("crate.car.png"), BuilderPhotoPaths.withPng(Path.of("crate.car.nbt")));
    }

    @Test
    @DisplayName("A name with no extension gains one rather than losing characters")
    void extensionlessNames() {
        assertEquals(Path.of("crate_car.png"), BuilderPhotoPaths.withPng(Path.of("crate_car")));
    }

    @Test
    @DisplayName("A dotfile keeps its whole name — the leading dot isn't an extension")
    void leadingDotIsNotAnExtension() {
        assertEquals(Path.of(".keep.png"), BuilderPhotoPaths.withPng(Path.of(".keep")));
    }

    // ---- kind ids ----

    @Test
    @DisplayName("Kind ids round-trip, and an unknown one resolves to nothing rather than a guess")
    void kindIds() {
        for (Kind k : Kind.values()) {
            assertEquals(Optional.of(k), Kind.fromId(k.id()));
        }
        assertEquals(Optional.of(Kind.CARRIAGE), Kind.fromId(" Carriage "));
        assertTrue(Kind.fromId("portal").isEmpty());
        assertTrue(Kind.fromId(null).isEmpty());
    }

    @Test
    @DisplayName("No id means no path — a photo is never written to a guessed name")
    void missingArgumentsResolveToNothing() {
        assertTrue(BuilderPhotoPaths.photoFor(Kind.CARRIAGE, "", null).isEmpty());
        assertTrue(BuilderPhotoPaths.photoFor(Kind.CARRIAGE, null, null).isEmpty());
        assertTrue(BuilderPhotoPaths.photoFor(null, "crate_car", null).isEmpty());
        assertTrue(BuilderPhotoPaths.photoFor(Kind.PART, "sandstone", null).isEmpty(),
                "a part without its kind has no directory to live in");
    }
}
