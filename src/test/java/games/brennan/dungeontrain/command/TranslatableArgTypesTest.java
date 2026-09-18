package games.brennan.dungeontrain.command;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every argument handed to {@code Component.translatable(key, args...)} must be a
 * {@code Component}, {@code Number}, {@code Boolean} or {@code String}.
 *
 * <p>{@code TranslatableContents} enforces that at serialisation time, not at compile time — so a
 * {@code BlockPos}, {@code Path} or {@code ResourceLocation} slipped in as a format arg compiles
 * fine and then throws {@code IllegalArgumentException} the moment the message is sent. The i18n
 * pass in #1447 converted hundreds of {@code Component.literal("… " + x)} calls (where any type
 * stringifies) into {@code translatable(key, x)}, and 28 of them carried a non-String {@code x}:
 * {@code /dungeontrain editor enter} teleported the player and then logged
 * {@code editor enter failed} instead of confirming. A grep cannot tell {@code origin} (BlockPos)
 * from {@code name} (String), so this test asks javac.
 *
 * <p>Only sources that mention {@code Component.translatable(} are attributed; symbols from the
 * rest of the mod resolve from {@code build/classes/java/main} on the test classpath, so this is a
 * partial compile, not a second build. Args whose static type is plain {@code Object} (ternaries
 * whose branches are both Component/String) or {@code Object[]} (varargs pass-through helpers) are
 * out of reach of a static check and are skipped.
 */
class TranslatableArgTypesTest {

    private static final String CALL = "Component.translatable(";
    private static final String COMPONENT = "net.minecraft.network.chat.Component";

    @Test
    void everyTranslatableArgIsComponentNumberBooleanOrString() throws IOException {
        Path sources = RepoPaths.root().resolve("src/main/java");
        List<Path> files = filesMentioning(sources, CALL);
        assertTrue(files.size() > 50, "expected many translatable call sites under " + sources + ", found " + files.size());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) fail("no system Java compiler — the test JVM must be a full JDK");
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            List<String> options = List.of("-cp", System.getProperty("java.class.path"), "-proc:none");
            JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostic -> { }, options, null, units);
            Iterable<? extends CompilationUnitTree> trees = task.parse();
            task.analyze();

            List<String> offenders = new ArrayList<>();
            int[] checked = {0};
            Checker checker = new Checker(task);
            for (CompilationUnitTree cu : trees) checker.scan(cu, offenders, checked);

            assertTrue(checked[0] > 500, "attributed suspiciously few translatable args: " + checked[0]);
            if (!offenders.isEmpty()) {
                fail("Component.translatable args that TranslatableContents will reject at runtime "
                    + "(stringify them — .toShortString() / .toString()):\n  " + String.join("\n  ", offenders));
            }
        }
    }

    private static List<Path> filesMentioning(Path root, String needle) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (Files.readString(p).contains(needle)) out.add(p);
            }
        }
        return out;
    }

    /** Walks one attributed compilation unit, collecting offending args. */
    private static final class Checker {
        private final Trees trees;
        private final Types types;
        private final TypeMirror string;
        private final TypeMirror number;
        private final TypeMirror bool;
        private final TypeMirror component;
        private final TypeMirror object;

        Checker(JavacTask task) {
            this.trees = Trees.instance(task);
            this.types = task.getTypes();
            Elements elements = task.getElements();
            this.string = elements.getTypeElement("java.lang.String").asType();
            this.number = elements.getTypeElement("java.lang.Number").asType();
            this.bool = elements.getTypeElement("java.lang.Boolean").asType();
            this.object = elements.getTypeElement("java.lang.Object").asType();
            TypeElement componentElement = elements.getTypeElement(COMPONENT);
            if (componentElement == null) fail(COMPONENT + " not on the test classpath — cannot type-check args");
            this.component = types.erasure(componentElement.asType());
        }

        void scan(CompilationUnitTree cu, List<String> offenders, int[] checked) {
            SourcePositions positions = trees.getSourcePositions();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                    if (isTranslatable(call)) {
                        List<? extends ExpressionTree> args = call.getArguments();
                        for (int i = 1; i < args.size(); i++) {
                            checked[0]++;
                            ExpressionTree arg = args.get(i);
                            TypeMirror type = trees.getTypeMirror(new TreePath(getCurrentPath(), arg));
                            if (!accepted(type)) offenders.add(describe(cu, positions, call, arg, type));
                        }
                    }
                    return super.visitMethodInvocation(call, unused);
                }
            }.scan(cu, null);
        }

        private static boolean isTranslatable(MethodInvocationTree call) {
            return call.getMethodSelect() instanceof MemberSelectTree select
                && select.getIdentifier().contentEquals("translatable")
                && select.getExpression().toString().endsWith("Component");
        }

        private boolean accepted(TypeMirror type) {
            if (type == null || type.getKind() == TypeKind.ERROR) return false;
            TypeMirror boxed = type.getKind().isPrimitive() ? types.boxedClass((PrimitiveType) type).asType() : type;
            // Plain Object (a ternary over Component/String branches) and Object[] (a varargs
            // pass-through such as EditorScreenLang) are unknowable statically — skip, don't guess.
            if (types.isSameType(boxed, object)) return true;
            if (boxed.getKind() == TypeKind.ARRAY) return true;
            return types.isAssignable(boxed, string)
                || types.isAssignable(boxed, number)
                || types.isAssignable(boxed, bool)
                || types.isAssignable(types.erasure(boxed), component);
        }

        private static String describe(CompilationUnitTree cu, SourcePositions positions, Tree call, Tree arg, TypeMirror type) {
            long line = cu.getLineMap().getLineNumber(positions.getStartPosition(cu, call));
            String file = cu.getSourceFile().getName();
            return file.substring(file.lastIndexOf('/') + 1) + ":" + line + "  " + type + "  <- " + arg;
        }
    }
}
