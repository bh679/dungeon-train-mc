package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier for a carriage variant — either one of the four hardcoded
 * {@link CarriageType} built-ins or a user-authored custom variant registered
 * in {@link CarriageVariantRegistry}.
 *
 * <p>The {@link #id()} is the canonical lowercase token used as the template
 * filename under {@code config/dungeontrain/templates/} and as the argument
 * token on {@code /dungeontrain editor ...} subcommands. Built-in ids are the
 * lowercased enum names; custom ids are validated against {@link #NAME_PATTERN}.
 */
public sealed interface CarriageVariant permits CarriageVariant.Builtin, CarriageVariant.Custom {

    Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    String id();

    /** True for {@link Builtin}, false for {@link Custom}. */
    boolean isBuiltin();

    static CarriageVariant of(CarriageType type) {
        return new Builtin(type);
    }

    static CarriageVariant custom(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Invalid variant name '" + name + "' — must match " + NAME_PATTERN.pattern());
        }
        for (CarriageType t : CarriageType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(name)) {
                throw new IllegalArgumentException(
                    "Name '" + name + "' is reserved for a built-in variant");
            }
        }
        return new Custom(name);
    }

    /** Returns true if {@code name} is one of the reserved built-in ids. */
    static boolean isReservedBuiltinName(String name) {
        for (CarriageType t : CarriageType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(name)) return true;
        }
        return false;
    }

    record Builtin(CarriageType type) implements CarriageVariant {
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

    record Custom(String name) implements CarriageVariant {
        public Custom {
            Objects.requireNonNull(name, "name");
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException(
                    "Invalid custom variant name '" + name + "'");
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
