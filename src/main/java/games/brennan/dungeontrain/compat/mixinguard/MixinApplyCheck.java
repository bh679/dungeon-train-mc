package games.brennan.dungeontrain.compat.mixinguard;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks, after a mixin has been applied, that its injectors actually took hold. The guarded configs set
 * {@code defaultRequire: 0}, so an injector whose injection point is gone fails without a word: Mixin
 * still merges the handler method into the target, it just never calls it. A handler nothing calls is
 * therefore the sign of a hook that did not land. Pure ASM, like {@link MixinTargetCheck}.
 *
 * <p>Only {@code @Inject} and {@code @Redirect} are checked. MixinExtras' injectors are applied after the
 * plugin's {@code postApply} hook runs, so they would always read as missing here.</p>
 */
public final class MixinApplyCheck {

    private static final Set<String> CHECKED_INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;");

    private MixinApplyCheck() {}

    /**
     * The mixin's injector handlers (by their name in the mixin class) that {@code target} never calls.
     *
     * @param target the target class after the mixin was applied
     * @param mixin  the mixin class as written
     */
    public static List<String> unhooked(ClassNode target, ClassNode mixin) {
        return mixin.methods.stream()
                .filter(MixinApplyCheck::isCheckedInjector)
                .map(handler -> handler.name)
                .filter(handler -> !isCalled(target, handler))
                .toList();
    }

    private static boolean isCheckedInjector(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations.stream().anyMatch(a -> CHECKED_INJECTORS.contains(a.desc));
    }

    /** Mixin renames a merged handler to {@code <kind>$<id>$<mod>$<name>}; the original name stays last. */
    private static boolean isMergedNameOf(String mergedName, String handler) {
        return mergedName.equals(handler) || mergedName.endsWith("$" + handler);
    }

    private static boolean isCalled(ClassNode target, String handler) {
        for (MethodNode method : target.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)
                        && isMergedNameOf(call.name, handler)) {
                    return true;
                }
            }
        }
        return false;
    }
}
