package games.brennan.dungeontrain.compat.mixinguard;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Predicate;

/**
 * Checks a third-party class's bytecode for the members and call sites a DT mixin needs. Pure ASM,
 * no Minecraft classes, so it runs during mixin bootstrap and in plain unit tests alike.
 */
public final class MixinTargetCheck {

    private MixinTargetCheck() {}

    /** The requirements {@code node} does not meet, as {@link MixinTargetRequirement#describe} lines. */
    public static List<String> missing(ClassNode node, List<MixinTargetRequirement> requirements) {
        return requirements.stream()
                .filter(r -> !isMet(node, r))
                .map(MixinTargetRequirement::describe)
                .toList();
    }

    static boolean isMet(ClassNode node, MixinTargetRequirement requirement) {
        return switch (requirement) {
            case MixinTargetRequirement.Method m -> node.methods.stream()
                    .anyMatch(mn -> mn.name.equals(m.name()) && mn.desc.equals(m.desc()));
            case MixinTargetRequirement.Field f -> node.fields.stream()
                    .anyMatch(fn -> fn.name.equals(f.name()) && fn.desc.equals(f.desc())
                            && ((fn.access & Opcodes.ACC_STATIC) != 0) == f.isStatic());
            case MixinTargetRequirement.Invokes i -> anyInstruction(node, i.inMethod(), insn ->
                    insn instanceof MethodInsnNode call && call.owner.equals(i.owner())
                            && call.name.equals(i.name()) && call.desc.equals(i.desc()));
            case MixinTargetRequirement.ReadsStatic r -> anyInstruction(node,
                    MixinTargetRequirement.Invokes.ANY_METHOD, insn ->
                    insn instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
                            && read.owner.equals(node.name) && read.name.equals(r.name()));
        };
    }

    private static boolean anyInstruction(ClassNode node, String inMethod, Predicate<AbstractInsnNode> match) {
        boolean anyMethod = MixinTargetRequirement.Invokes.ANY_METHOD.equals(inMethod);
        for (MethodNode method : node.methods) {
            if (!anyMethod && !method.name.equals(inMethod)) continue;
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (match.test(insn)) return true;
            }
        }
        return false;
    }
}
