package games.brennan.dungeontrain.compat.mixinguard;

/**
 * One thing a DT mixin needs to find in a third-party class — a method, a field, or a call site its
 * injector hooks. Names and descriptors are JVM internal form ({@code java/util/Set},
 * {@code (Ljava/util/Collection;)Ljava/util/Set;}). Checked by {@link MixinTargetCheck}.
 */
public sealed interface MixinTargetRequirement {

    /** How a miss reads in the startup WARN. */
    String describe();

    /** A method named {@code name} with descriptor {@code desc}. */
    record Method(String name, String desc) implements MixinTargetRequirement {
        @Override
        public String describe() {
            return "method " + name + desc;
        }
    }

    /** A field named {@code name} with descriptor {@code desc}, static or not as given. */
    record Field(String name, String desc, boolean isStatic) implements MixinTargetRequirement {
        @Override
        public String describe() {
            return (isStatic ? "static field " : "field ") + name + ":" + desc;
        }
    }

    /**
     * A call to {@code owner.name desc} inside method {@code inMethod} ({@link #ANY_METHOD} for any
     * method of the class) — the injection point of a {@code @Redirect}.
     */
    record Invokes(String inMethod, String owner, String name, String desc) implements MixinTargetRequirement {
        public static final String ANY_METHOD = "*";

        @Override
        public String describe() {
            String where = ANY_METHOD.equals(inMethod) ? "any method" : inMethod;
            return "call to " + owner + "." + name + desc + " in " + where;
        }
    }

    /** A {@code GETSTATIC} of the class's own field {@code name} in any method — a field {@code @Redirect}. */
    record ReadsStatic(String name) implements MixinTargetRequirement {
        @Override
        public String describe() {
            return "read of static field " + name;
        }
    }

    /**
     * A {@code GETSTATIC} ({@code write} false) or {@code PUTSTATIC} of the class's own field {@code name}
     * inside method {@code inMethod} — a field {@code @Redirect} aimed at one method or lambda.
     */
    record AccessesStatic(String inMethod, String name, boolean write) implements MixinTargetRequirement {
        @Override
        public String describe() {
            return (write ? "write" : "read") + " of static field " + name + " in " + inMethod;
        }
    }

    static MixinTargetRequirement method(String name, String desc) {
        return new Method(name, desc);
    }

    static MixinTargetRequirement staticField(String name, String desc) {
        return new Field(name, desc, true);
    }

    static MixinTargetRequirement field(String name, String desc) {
        return new Field(name, desc, false);
    }

    static MixinTargetRequirement invokes(String inMethod, String owner, String name, String desc) {
        return new Invokes(inMethod, owner, name, desc);
    }

    static MixinTargetRequirement readsStatic(String name) {
        return new ReadsStatic(name);
    }

    static MixinTargetRequirement readsStaticIn(String inMethod, String name) {
        return new AccessesStatic(inMethod, name, false);
    }

    static MixinTargetRequirement writesStaticIn(String inMethod, String name) {
        return new AccessesStatic(inMethod, name, true);
    }
}
