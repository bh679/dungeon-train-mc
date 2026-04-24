package games.brennan.dungeontrain.train;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier for a carriage interior-contents variant — either the hardcoded
 * {@link ContentsType#DEFAULT} built-in (a single stone pressure plate at the
 * floor centre) or a user-authored custom contents registered in
 * {@link CarriageContentsRegistry}.
 *
 * <p>The {@link #id()} is the canonical lowercase token used as the template
 * filename under {@code config/dungeontrain/contents/} and as the argument
 * token on {@code /dungeontrain editor contents ...} subcommands. Built-in ids
 * are the lowercased enum names; custom ids are validated against
 * {@link #NAME_PATTERN}.
 *
 * <p>Parallel to {@link CarriageVariant} (which covers the outer shell) —
 * contents variants are picked independently at spawn time, so any shell can
 * pair with any contents.
 */
public sealed interface CarriageContents permits CarriageContents.Builtin, CarriageContents.Custom {

    Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    /** Built-in contents variants that always exist regardless of user authoring. */
    enum ContentsType {
        DEFAULT
    }

    String id();

    /** True for {@link Builtin}, false for {@link Custom}. */
    boolean isBuiltin();

    static CarriageContents of(ContentsType type) {
        return new Builtin(type);
    }

    static CarriageContents custom(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Invalid contents name '" + name + "' — must match " + NAME_PATTERN.pattern());
        }
        if (isReservedBuiltinName(name)) {
            throw new IllegalArgumentException(
                "Name '" + name + "' is reserved for a built-in contents variant");
        }
        return new Custom(name);
    }

    /** Returns true if {@code name} is one of the reserved built-in ids. */
    static boolean isReservedBuiltinName(String name) {
        for (ContentsType t : ContentsType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(name)) return true;
        }
        return false;
    }

    record Builtin(ContentsType type) implements CarriageContents {
        public Builtin {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public String id() {
            return type.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean isBuiltin() {
            return true;
        }
    }

    record Custom(String name) implements CarriageContents {
        public Custom {
            Objects.requireNonNull(name, "name");
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "Invalid custom contents name '" + name + "'");
            }
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }
    }
}
