package games.brennan.dungeontrain.compat.mixinguard;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link MixinApplyCheck} against hand-built classes: a called handler passes, a merged-but-uncalled one is reported. */
final class MixinApplyCheckTest {

    private static final String TARGET = "lib/Target";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

    private static MethodNode method(String name, String annotation) {
        MethodNode node = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, "()V", null, null);
        if (annotation != null) node.visibleAnnotations = List.of(new AnnotationNode(annotation));
        node.instructions.add(new InsnNode(Opcodes.RETURN));
        return node;
    }

    private static ClassNode mixin(MethodNode... methods) {
        ClassNode node = new ClassNode();
        node.name = "dt/SomeMixin";
        node.methods.addAll(List.of(methods));
        return node;
    }

    /** The target after apply: every handler merged under its renamed name, {@code run()} calling {@code called}. */
    private static ClassNode applied(List<String> merged, List<String> called) {
        ClassNode node = new ClassNode();
        node.name = TARGET;
        MethodNode run = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        for (String name : called) {
            run.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TARGET, name, "()V", false));
        }
        run.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(run);
        merged.forEach(name -> node.methods.add(method(name, null)));
        return node;
    }

    @Test
    void calledHandlersPass() {
        ClassNode mixin = mixin(method("dungeontrain$head", INJECT), method("dungeontrain$order", REDIRECT));
        List<String> merged = List.of("handler$zza000$dungeontrain$dungeontrain$head",
                "redirect$zza000$dungeontrain$dungeontrain$order");
        assertEquals(List.of(), MixinApplyCheck.unhooked(applied(merged, merged), mixin));
    }

    @Test
    void mergedButUncalledHandlerIsReported() {
        ClassNode mixin = mixin(method("dungeontrain$head", INJECT), method("dungeontrain$order", REDIRECT));
        List<String> merged = List.of("handler$zza000$dungeontrain$dungeontrain$head",
                "redirect$zza000$dungeontrain$dungeontrain$order");
        assertEquals(List.of("dungeontrain$order"),
                MixinApplyCheck.unhooked(applied(merged, merged.subList(0, 1)), mixin));
    }

    @Test
    void aCallToAnotherClassDoesNotCount() {
        ClassNode mixin = mixin(method("dungeontrain$head", INJECT));
        ClassNode target = applied(List.of(), List.of());
        target.methods.get(0).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "lib/Other",
                "handler$zza000$dungeontrain$dungeontrain$head", "()V", false));
        assertEquals(List.of("dungeontrain$head"), MixinApplyCheck.unhooked(target, mixin));
    }

    @Test
    void plainMethodsAndLateInjectorsAreNotChecked() {
        ClassNode mixin = mixin(method("dungeontrain$helper", null), method("dungeontrain$wrap", WRAP_OPERATION));
        assertEquals(List.of(), MixinApplyCheck.unhooked(applied(List.of(), List.of()), mixin));
    }
}
