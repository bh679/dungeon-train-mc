package games.brennan.dungeontrain.compat.mixinguard;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.field;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.invokes;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.method;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.readsStatic;
import static games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement.staticField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MixinTargetCheck} against hand-built classes: intact passes, each kind of drift is reported. */
final class MixinTargetCheckTest {

    private static final String OWNER = "lib/Target";
    private static final String DIRS = "[Lnet/minecraft/core/Direction;";

    /** A class with {@code static DIR}, instance {@code data}, and {@code rebuild()} that reads DIR and calls Set.forEach. */
    private static ClassNode target(String methodName, String fieldName, String calledName) {
        ClassNode node = new ClassNode();
        node.name = OWNER;
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, fieldName, DIRS, null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "data", "Ljava/lang/Object;", null, null));
        MethodNode rebuild = new MethodNode(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        rebuild.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, fieldName, DIRS));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", calledName,
                "(Ljava/util/function/Consumer;)V", true));
        rebuild.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(rebuild);
        return node;
    }

    private static final List<MixinTargetRequirement> NEEDS = List.of(
            method("rebuild", "()V"),
            staticField("DIR", DIRS),
            field("data", "Ljava/lang/Object;"),
            invokes("rebuild", "java/util/Set", "forEach", "(Ljava/util/function/Consumer;)V"),
            readsStatic("DIR"));

    @Test
    void intactClassHasNoMisses() {
        assertEquals(List.of(), MixinTargetCheck.missing(target("rebuild", "DIR", "forEach"), NEEDS));
    }

    @Test
    void renamedMethodIsReportedWithItsCallSite() {
        List<String> misses = MixinTargetCheck.missing(target("rebuildAll", "DIR", "forEach"), NEEDS);
        assertEquals(List.of("method rebuild()V",
                "call to java/util/Set.forEach(Ljava/util/function/Consumer;)V in rebuild"), misses);
    }

    @Test
    void renamedStaticFieldIsReportedWithItsRead() {
        List<String> misses = MixinTargetCheck.missing(target("rebuild", "DIRS", "forEach"), NEEDS);
        assertEquals(List.of("static field DIR:" + DIRS, "read of static field DIR"), misses);
    }

    @Test
    void movedCallIsReported() {
        List<String> misses = MixinTargetCheck.missing(target("rebuild", "DIR", "iterator"), NEEDS);
        assertEquals(1, misses.size());
        assertTrue(misses.get(0).startsWith("call to java/util/Set.forEach"));
    }

    @Test
    void wrongDescriptorOrStaticnessIsAMiss() {
        ClassNode node = target("rebuild", "DIR", "forEach");
        assertEquals(1, MixinTargetCheck.missing(node, List.of(method("rebuild", "(I)V"))).size());
        assertEquals(1, MixinTargetCheck.missing(node, List.of(field("DIR", DIRS))).size());
        assertEquals(1, MixinTargetCheck.missing(node, List.of(staticField("data", "Ljava/lang/Object;"))).size());
    }

    @Test
    void anyMethodCallSiteMatchesWherever() {
        ClassNode node = target("place", "DIR", "forEach");
        assertEquals(List.of(), MixinTargetCheck.missing(node, List.of(invokes(
                MixinTargetRequirement.Invokes.ANY_METHOD, "java/util/Set", "forEach",
                "(Ljava/util/function/Consumer;)V"))));
    }
}
