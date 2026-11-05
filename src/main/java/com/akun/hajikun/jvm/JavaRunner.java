package com.akun.hajikun.jvm;

import com.akun.hajikun.vm.VirtualFileSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * 迷你 JRE：直接解释 {@link JavaAst}，不生成字节码。
 *
 * <p>类路径就是虚拟文件系统：包名与目录一一对应，运行 {@code java com.demo.Main}
 * 时从当前目录的 {@code com/demo/Main.class} 读取，需要的其它类按包路径惰性加载。
 * 解释器带步数、输出长度与调用深度上限，避免玩家在服务器线程里写出死循环。</p>
 */
final class JavaRunner {
    private static final int MAX_STEPS = 3_000_000;
    private static final int MAX_OUTPUT = 24_000;
    private static final int MAX_DEPTH = 200;
    private static final Object PRINT_STREAM = new Object();
    private static final Object MISSING = new Object();
    private static final ReturnSignal BREAK = new ReturnSignal(null);
    private static final ReturnSignal CONTINUE = new ReturnSignal(null);

    private final VirtualFileSystem fs;
    private final List<String> roots;
    private final StringBuilder out = new StringBuilder();
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private int steps;
    private int depth;
    private JavaObject currentThis;
    private ClassInfo currentClass;

    JavaRunner(VirtualFileSystem fs, List<String> roots) {
        this.fs = fs;
        this.roots = new ArrayList<>();
        for (String root : roots) {
            String value = root == null || root.isBlank() ? "/" : root;
            if (!this.roots.contains(value)) {
                this.roots.add(value);
            }
        }
        if (this.roots.isEmpty()) {
            this.roots.add("/");
        }
    }

    String run(JavaAst.Unit unit, String className, List<String> programArgs) {
        long started = System.currentTimeMillis();
        try {
            this.register(unit);
            ClassInfo cls = this.resolveClass(className, unit.pkg());
            if (cls == null) {
                return "错误: 找不到类 " + className + "\n提示：用 javac 编译后，从包含包目录的文件夹运行 java <包名>.<类名>。";
            }
            this.ensureInit(cls);
            JavaAst.MethodDecl main = this.findMethod(cls, "main", 1);
            if (main == null || !main.isStatic()) {
                main = this.findMethod(cls, "main", 0);
            }
            if (main == null || !main.isStatic()) {
                this.fail("java.lang.NoSuchMethodError: " + cls.fullName
                        + " 中没有 public static void main(String[] args)");
            }
            JavaArray argv = new JavaArray("String", programArgs.size());
            for (int i = 0; i < programArgs.size(); i++) {
                argv.values[i] = programArgs.get(i);
            }
            this.invokeStatic(cls, main, main.params().size() == 1 ? List.of(argv) : List.of());
            long ms = Math.max(0L, System.currentTimeMillis() - started);
            return this.finish(0, ms);
        } catch (ExitSignal exit) {
            return this.finish(exit.code, Math.max(0L, System.currentTimeMillis() - started));
        } catch (Failure failure) {
            return this.out + "Exception in thread \"main\" " + failure.getMessage();
        } catch (StackOverflowError error) {
            return this.out + "Exception in thread \"main\" java.lang.StackOverflowError";
        }
    }

    private String finish(int code, long ms) {
        StringBuilder text = new StringBuilder(this.out);
        if (text.length() == 0) {
            text.append("(程序没有输出)\n");
        } else if (text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append("进程结束，退出码 ").append(code)
                .append("（").append(ms).append(" ms，").append(this.steps).append(" 步）");
        return text.toString();
    }

    // ------------------------------------------------------------------ 类加载

    private void register(JavaAst.Unit unit) {
        for (JavaAst.ClassDecl decl : unit.types()) {
            String full = unit.pkg().isBlank() ? decl.name() : unit.pkg() + "." + decl.name();
            this.classes.putIfAbsent(full, new ClassInfo(full, unit.pkg(), decl));
        }
    }

    private boolean loadClassFile(String path) {
        String text = this.fs.read(path);
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.startsWith(JavaToolchain.CLASS_HEADER)) {
            text = text.substring(JavaToolchain.CLASS_HEADER.length());
        }
        try {
            this.register(JavaParser.parse(text));
            return true;
        } catch (JavaSyntaxException exception) {
            return false;
        }
    }

    private ClassInfo resolveClass(String name, String pkg) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String type = name.trim();
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2);
        }
        List<String> candidates = new ArrayList<>();
        if (type.contains(".")) {
            candidates.add(type);
        } else {
            if (pkg != null && !pkg.isBlank()) {
                candidates.add(pkg + "." + type);
            }
            candidates.add(type);
        }
        for (String candidate : candidates) {
            ClassInfo found = this.classes.get(candidate);
            if (found != null) {
                return found;
            }
        }
        for (String root : this.roots) {
            for (String candidate : candidates) {
                String path = root.endsWith("/") ? root : root + "/";
                path = path + candidate.replace('.', '/') + ".class";
                if (this.loadClassFile(path)) {
                    ClassInfo found = this.classes.get(candidate);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        for (ClassInfo info : this.classes.values()) {
            if (info.simple.equals(type)) {
                return info;
            }
        }
        return null;
    }

    private ClassInfo superOf(ClassInfo info) {
        if (info == null || info.decl.superName() == null || info.decl.superName().isBlank()) {
            return null;
        }
        return this.resolveClass(info.decl.superName(), info.pkg);
    }

    private void ensureInit(ClassInfo info) {
        if (info == null || info.initStarted) {
            return;
        }
        info.initStarted = true;
        ClassInfo parent = this.superOf(info);
        this.ensureInit(parent);
        JavaObject savedThis = this.currentThis;
        ClassInfo savedClass = this.currentClass;
        try {
            for (JavaAst.FieldDecl field : info.decl.fields()) {
                if (!field.isStatic()) {
                    continue;
                }
                Object value = field.init() == null ? defaultFor(field.type()) : this.eval(field.init(), new Env(null));
                info.statics.put(field.name(), coerce(value, field.type()));
            }
        } finally {
            this.currentThis = savedThis;
            this.currentClass = savedClass;
        }
    }

    private Object getStatic(ClassInfo info, String name) {
        for (ClassInfo cursor = info; cursor != null; cursor = this.superOf(cursor)) {
            if (cursor.statics.containsKey(name)) {
                return cursor.statics.get(name);
            }
        }
        return MISSING;
    }

    private JavaAst.FieldDecl findField(ClassInfo info, String name) {
        for (ClassInfo cursor = info; cursor != null; cursor = this.superOf(cursor)) {
            for (JavaAst.FieldDecl field : cursor.decl.fields()) {
                if (field.name().equals(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private JavaAst.MethodDecl findMethod(ClassInfo info, String name, int arity) {
        for (ClassInfo cursor = info; cursor != null; cursor = this.superOf(cursor)) {
            for (JavaAst.MethodDecl method : cursor.decl.methods()) {
                if (method.name().equals(name) && method.params().size() == arity && !method.isAbstract()) {
                    return method;
                }
            }
        }
        return null;
    }

    private JavaAst.MethodDecl findConstructor(ClassInfo info, int arity) {
        for (JavaAst.MethodDecl method : info.decl.methods()) {
            if (method.name().equals(info.simple) && method.params().size() == arity) {
                return method;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 调用

    private Object invokeStatic(ClassInfo cls, JavaAst.MethodDecl method, List<Object> args) {
        return this.invoke(cls, null, method, args);
    }

    private Object invoke(ClassInfo cls, JavaObject self, JavaAst.MethodDecl method, List<Object> args) {
        if (method.body() == null) {
            return defaultFor(method.returnType());
        }
        if (++this.depth > MAX_DEPTH) {
            this.fail("java.lang.StackOverflowError: 调用层级超过 " + MAX_DEPTH);
        }
        JavaObject savedThis = this.currentThis;
        ClassInfo savedClass = this.currentClass;
        this.currentThis = method.isStatic() ? null : self;
        this.currentClass = cls;
        try {
            Env env = new Env(null);
            for (int i = 0; i < method.params().size(); i++) {
                JavaAst.Param param = method.params().get(i);
                Object value = i < args.size() ? args.get(i) : null;
                env.define(param.name(), param.type(), coerce(value, param.type()));
            }
            this.exec(method.body(), env);
            return defaultFor(method.returnType());
        } catch (ReturnSignal signal) {
            return coerce(signal.value, method.returnType());
        } finally {
            this.currentThis = savedThis;
            this.currentClass = savedClass;
            this.depth--;
        }
    }

    private Object instantiate(ClassInfo cls, List<Object> args) {
        this.ensureInit(cls);
        JavaObject object = new JavaObject(cls);
        this.initInstanceFields(cls, object);
        JavaAst.MethodDecl constructor = this.findConstructor(cls, args.size());
        if (constructor != null) {
            this.invoke(cls, object, constructor, args);
        }
        return object;
    }

    private void initInstanceFields(ClassInfo cls, JavaObject object) {
        ClassInfo parent = this.superOf(cls);
        if (parent != null) {
            this.initInstanceFields(parent, object);
        }
        JavaObject savedThis = this.currentThis;
        ClassInfo savedClass = this.currentClass;
        this.currentThis = object;
        this.currentClass = cls;
        try {
            for (JavaAst.FieldDecl field : cls.decl.fields()) {
                if (field.isStatic()) {
                    continue;
                }
                Object value = field.init() == null ? defaultFor(field.type()) : this.eval(field.init(), new Env(null));
                object.fields.put(field.name(), coerce(value, field.type()));
            }
        } finally {
            this.currentThis = savedThis;
            this.currentClass = savedClass;
        }
    }

    // ------------------------------------------------------------------ 语句

    private void exec(JavaAst.Stmt stmt, Env env) {
        this.step();
        if (stmt instanceof JavaAst.Block block) {
            Env inner = new Env(env);
            for (JavaAst.Stmt child : block.statements()) {
                this.exec(child, inner);
            }
            return;
        }
        if (stmt instanceof JavaAst.Empty) {
            return;
        }
        if (stmt instanceof JavaAst.VarDecl decl) {
            Object value;
            if (decl.init() instanceof JavaAst.New literal && literal.type().isEmpty()
                    && literal.init() != null) {
                value = this.arrayFromLiteral(decl.type(), literal.init(), env);
            } else {
                value = decl.init() == null ? defaultFor(decl.type()) : this.eval(decl.init(), env);
            }
            env.define(decl.name(), decl.type(), coerce(value, decl.type()));
            return;
        }
        if (stmt instanceof JavaAst.ExprStmt expr) {
            this.eval(expr.expr(), env);
            return;
        }
        if (stmt instanceof JavaAst.If branch) {
            if (this.toBool(this.eval(branch.cond(), env))) {
                this.exec(branch.then(), env);
            } else if (branch.otherwise() != null) {
                this.exec(branch.otherwise(), env);
            }
            return;
        }
        if (stmt instanceof JavaAst.While loop) {
            while (this.toBool(this.eval(loop.cond(), env))) {
                try {
                    this.exec(loop.body(), env);
                } catch (ReturnSignal signal) {
                    if (signal == BREAK) {
                        break;
                    }
                    if (signal != CONTINUE) {
                        throw signal;
                    }
                }
            }
            return;
        }
        if (stmt instanceof JavaAst.DoWhile loop) {
            do {
                try {
                    this.exec(loop.body(), env);
                } catch (ReturnSignal signal) {
                    if (signal == BREAK) {
                        break;
                    }
                    if (signal != CONTINUE) {
                        throw signal;
                    }
                }
            } while (this.toBool(this.eval(loop.cond(), env)));
            return;
        }
        if (stmt instanceof JavaAst.For loop) {
            Env inner = new Env(env);
            if (loop.init() != null) {
                this.exec(loop.init(), inner);
            }
            while (loop.cond() == null || this.toBool(this.eval(loop.cond(), inner))) {
                try {
                    this.exec(loop.body(), inner);
                } catch (ReturnSignal signal) {
                    if (signal == BREAK) {
                        break;
                    }
                    if (signal != CONTINUE) {
                        throw signal;
                    }
                }
                for (JavaAst.Expr update : loop.update()) {
                    this.eval(update, inner);
                }
            }
            return;
        }
        if (stmt instanceof JavaAst.ForEach loop) {
            Object iterable = this.eval(loop.iterable(), env);
            for (Object item : this.iterable(iterable)) {
                Env inner = new Env(env);
                inner.define(loop.name(), loop.type(), coerce(item, loop.type()));
                try {
                    this.exec(loop.body(), inner);
                } catch (ReturnSignal signal) {
                    if (signal == BREAK) {
                        break;
                    }
                    if (signal != CONTINUE) {
                        throw signal;
                    }
                }
            }
            return;
        }
        if (stmt instanceof JavaAst.Return ret) {
            throw new ReturnSignal(ret.value() == null ? null : this.eval(ret.value(), env));
        }
        if (stmt instanceof JavaAst.Break) {
            throw BREAK;
        }
        if (stmt instanceof JavaAst.Continue) {
            throw CONTINUE;
        }
    }

    private List<Object> iterable(Object value) {
        List<Object> list = new ArrayList<>();
        if (value == null) {
            this.fail("java.lang.NullPointerException: 不能在 null 上做 for-each");
        }
        if (value instanceof JavaArray array) {
            list.addAll(Arrays.asList(array.values));
        } else if (value instanceof List<?> items) {
            list.addAll(items);
        } else if (value instanceof String text) {
            for (int i = 0; i < text.length(); i++) {
                list.add(text.charAt(i));
            }
        } else if (value instanceof Map<?, ?> map) {
            list.addAll(map.values());
        } else {
            this.fail("不支持对 " + value.getClass().getSimpleName() + " 做 for-each");
        }
        return list;
    }

    // ------------------------------------------------------------------ 表达式

    private Object eval(JavaAst.Expr expr, Env env) {
        this.step();
        if (expr instanceof JavaAst.Literal literal) {
            return literal.value();
        }
        if (expr instanceof JavaAst.Name name) {
            return this.lookup(name.id(), env);
        }
        if (expr instanceof JavaAst.This) {
            if (this.currentThis == null) {
                this.fail("java.lang.IllegalStateException: 静态方法里不能使用 this");
            }
            return this.currentThis;
        }
        if (expr instanceof JavaAst.Super) {
            return this.currentThis;
        }
        if (expr instanceof JavaAst.Field field) {
            return this.evalField(field, env);
        }
        if (expr instanceof JavaAst.Call call) {
            return this.evalCall(call, env);
        }
        if (expr instanceof JavaAst.New creation) {
            return this.evalNew(creation, env);
        }
        if (expr instanceof JavaAst.Index index) {
            Object array = this.eval(index.array(), env);
            int position = this.toInt(this.eval(index.index(), env));
            if (array instanceof JavaArray values) {
                return values.get(position);
            }
            if (array instanceof List<?> items) {
                return items.get(position);
            }
            this.fail("java.lang.RuntimeException: 不能对非数组使用下标");
        }
        if (expr instanceof JavaAst.Unary unary) {
            return this.evalUnary(unary, env);
        }
        if (expr instanceof JavaAst.Binary binary) {
            return this.evalBinary(binary, env);
        }
        if (expr instanceof JavaAst.Assign assign) {
            return this.evalAssign(assign, env);
        }
        if (expr instanceof JavaAst.Ternary ternary) {
            return this.toBool(this.eval(ternary.cond(), env))
                    ? this.eval(ternary.yes(), env) : this.eval(ternary.no(), env);
        }
        if (expr instanceof JavaAst.Cast cast) {
            return this.cast(cast.type(), this.eval(cast.expr(), env));
        }
        this.fail("无法求值的表达式");
        return null;
    }

    private Object lookup(String name, Env env) {
        Object value = env.get(name);
        if (value != MISSING) {
            return value;
        }
        if (this.currentThis != null) {
            JavaAst.FieldDecl field = this.findField(this.currentThis.type, name);
            if (field != null && !field.isStatic() && this.currentThis.fields.containsKey(name)) {
                return this.currentThis.fields.get(name);
            }
        }
        if (this.currentClass != null) {
            Object constant = this.getStatic(this.currentClass, name);
            if (constant != MISSING) {
                return constant;
            }
        }
        this.fail("java.lang.Error: 找不到变量 " + name + this.location());
        return null;
    }

    private Object evalField(JavaAst.Field field, Env env) {
        String qualified = qualifiedNameOf(field.target());
        if (qualified != null && env.get(qualified) == MISSING) {
            String simple = simpleName(qualified);
            if (this.isBuiltinStaticClass(simple)) {
                Object builtin = this.builtinStaticField(simple, field.name());
                if (builtin != MISSING) {
                    return builtin;
                }
            }
            ClassInfo qualifiedClass = this.resolveClass(qualified, this.pkg());
            if (qualifiedClass != null) {
                this.ensureInit(qualifiedClass);
                Object value = this.getStatic(qualifiedClass, field.name());
                if (value != MISSING) {
                    return value;
                }
            }
        }
        if (field.target() instanceof JavaAst.Name ref) {
            Object local = env.get(ref.id());
            if (local == MISSING && !(this.currentThis != null
                    && this.findField(this.currentThis.type, ref.id()) != null)) {
                Object builtin = this.builtinStaticField(ref.id(), field.name());
                if (builtin != MISSING) {
                    return builtin;
                }
                ClassInfo cls = this.resolveClass(ref.id(), this.pkg());
                if (cls != null) {
                    this.ensureInit(cls);
                    Object value = this.getStatic(cls, field.name());
                    if (value != MISSING) {
                        return value;
                    }
                    this.fail("java.lang.NoSuchFieldError: " + ref.id() + "." + field.name() + this.location());
                }
            }
        }
        Object target = this.eval(field.target(), env);
        if (target == null) {
            this.fail("java.lang.NullPointerException: 读取字段 " + field.name() + " 时对象为 null");
        }
        if (target instanceof JavaObject object) {
            JavaAst.FieldDecl decl = this.findField(object.type, field.name());
            if (decl == null || decl.isStatic()) {
                this.fail("java.lang.NoSuchFieldError: " + field.name() + this.location());
            }
            return object.fields.get(field.name());
        }
        if (target instanceof JavaArray array) {
            if (field.name().equals("length")) {
                return array.values.length;
            }
        }
        if (target instanceof List<?> items && field.name().equals("size")) {
            return items.size();
        }
        this.fail("java.lang.NoSuchFieldError: " + field.name() + this.location());
        return null;
    }

    private Object evalCall(JavaAst.Call call, Env env) {
        List<Object> args = new ArrayList<>();
        for (JavaAst.Expr arg : call.args()) {
            args.add(this.eval(arg, env));
        }
        JavaAst.Expr target = call.target();
        String name = call.name();
        if (target == null) {
            JavaAst.MethodDecl method = this.findMethod(this.currentClass, name, args.size());
            if (method == null) {
                this.fail("java.lang.NoSuchMethodError: 找不到方法 " + name
                        + "(" + args.size() + " 个参数)" + this.location());
            }
            if (method.isStatic()) {
                return this.invokeStatic(this.currentClass, method, args);
            }
            if (this.currentThis == null) {
                this.fail("java.lang.IllegalStateException: 静态方法里不能调用实例方法 " + name);
            }
            return this.invoke(this.currentThis.type, this.currentThis, method, args);
        }
        if (target instanceof JavaAst.Name ref) {
            Object local = env.get(ref.id());
            if (local == MISSING && this.currentThis != null) {
                JavaAst.FieldDecl field = this.findField(this.currentThis.type, ref.id());
                if (field != null && !field.isStatic()) {
                    local = this.currentThis.fields.get(ref.id());
                }
            }
            if (local != MISSING) {
                return this.callOnValue(local, name, args);
            }
            if (this.isBuiltinStaticClass(ref.id())) {
                return this.builtinStaticCall(ref.id(), name, args);
            }
            ClassInfo cls = this.resolveClass(ref.id(), this.pkg());
            if (cls != null) {
                this.ensureInit(cls);
                JavaAst.MethodDecl method = this.findMethod(cls, name, args.size());
                if (method == null) {
                    this.fail("java.lang.NoSuchMethodError: " + ref.id() + " 没有方法 " + name);
                }
                return this.invokeStatic(cls, method, args);
            }
            this.fail("java.lang.Error: 找不到类或变量 " + ref.id() + this.location());
        }
        String qualified = qualifiedNameOf(target);
        if (qualified != null && env.get(qualified) == MISSING) {
            String simple = simpleName(qualified);
            if (this.isBuiltinStaticClass(simple)) {
                return this.builtinStaticCall(simple, name, args);
            }
            ClassInfo qualifiedClass = this.resolveClass(qualified, this.pkg());
            if (qualifiedClass != null) {
                this.ensureInit(qualifiedClass);
                JavaAst.MethodDecl method = this.findMethod(qualifiedClass, name, args.size());
                if (method == null) {
                    this.fail("java.lang.NoSuchMethodError: " + qualified + " 没有方法 " + name);
                }
                return this.invokeStatic(qualifiedClass, method, args);
            }
        }
        if (target instanceof JavaAst.This) {
            if ("<init>".equals(name) && this.currentThis != null) {
                JavaAst.MethodDecl chained = this.findConstructor(this.currentThis.type, args.size());
                if (chained != null) {
                    this.invoke(this.currentThis.type, this.currentThis, chained, args);
                }
                return null;
            }
        }
        if (target instanceof JavaAst.Super) {
            ClassInfo parent = this.superOf(this.currentClass);
            if (parent == null) {
                this.fail("java.lang.IllegalStateException: 没有父类");
            }
            if ("<init>".equals(name)) {
                JavaAst.MethodDecl chained = this.findConstructor(parent, args.size());
                if (chained != null) {
                    this.invoke(parent, this.currentThis, chained, args);
                }
                return null;
            }
            JavaAst.MethodDecl method = this.findMethod(parent, name, args.size());
            if (method == null) {
                this.fail("java.lang.NoSuchMethodError: 父类没有方法 " + name);
            }
            return this.invoke(parent, this.currentThis, method, args);
        }
        Object receiver = this.eval(target, env);
        return this.callOnValue(receiver, name, args);
    }

    private Object callOnValue(Object receiver, String name, List<Object> args) {
        if (receiver == null) {
            this.fail("java.lang.NullPointerException: 调用 " + name + "() 时对象为 null" + this.location());
        }
        if (receiver == PRINT_STREAM) {
            return this.printStream(name, args);
        }
        if (receiver instanceof JavaObject object) {
            JavaAst.MethodDecl method = this.findMethod(object.type, name, args.size());
            if (method != null) {
                return this.invoke(object.type, object, method, args);
            }
        }
        if (name.equals("toString") && args.isEmpty()) {
            return this.str(receiver);
        }
        if (name.equals("equals") && args.size() == 1) {
            return this.eq(receiver, args.get(0));
        }
        if (name.equals("hashCode") && args.isEmpty()) {
            return receiver.hashCode();
        }
        if (receiver instanceof String text) {
            return this.stringCall(text, name, args);
        }
        if (receiver instanceof List<?> list) {
            return this.listCall(list, name, args);
        }
        if (receiver instanceof Map<?, ?> map) {
            return this.mapCall(map, name, args);
        }
        if (receiver instanceof StringBuilder builder) {
            return this.builderCall(builder, name, args);
        }
        if (receiver instanceof JavaArray array) {
            if (name.equals("clone") && args.isEmpty()) {
                return new JavaArray(array.elementType, array.values.clone());
            }
        }
        this.fail("java.lang.NoSuchMethodError: " + receiver.getClass().getSimpleName()
                + " 没有方法 " + name + this.location());
        return null;
    }

    private Object evalNew(JavaAst.New creation, Env env) {
        if (creation.arraySize() != null) {
            int size = this.toInt(this.eval(creation.arraySize(), env));
            if (size < 0) {
                this.fail("java.lang.NegativeArraySizeException: " + size);
            }
            String element = elementType(creation.type());
            JavaArray array = new JavaArray(element, size);
            for (int i = 0; i < size; i++) {
                array.values[i] = defaultFor(element);
            }
            return array;
        }
        if (creation.init() != null) {
            String element = creation.type().isEmpty() ? "Object" : elementType(creation.type());
            JavaArray array = new JavaArray(element, creation.init().size());
            for (int i = 0; i < creation.init().size(); i++) {
                array.values[i] = this.eval(creation.init().get(i), env);
            }
            return array;
        }
        List<Object> args = new ArrayList<>();
        for (JavaAst.Expr arg : creation.args()) {
            args.add(this.eval(arg, env));
        }
        String base = simpleName(creation.type());
        switch (base) {
            case "ArrayList", "List", "LinkedList", "Vector" -> {
                List<Object> list = new ArrayList<>();
                if (!args.isEmpty() && args.get(0) instanceof List<?> copy) {
                    list.addAll(copy);
                }
                return list;
            }
            case "HashMap", "Map", "LinkedHashMap" -> {
                return new LinkedHashMap<Object, Object>();
            }
            case "StringBuilder", "StringBuffer" -> {
                StringBuilder builder = new StringBuilder();
                if (!args.isEmpty()) {
                    builder.append(this.str(args.get(0)));
                }
                return builder;
            }
            case "String" -> this.fail("String 是不可变对象，请用字面量或 StringBuilder");
            case "Integer", "Long", "Double", "Boolean" -> {
                return args.isEmpty() ? defaultFor(base) : this.cast(base, args.get(0));
            }
            case "Object" -> {
                return new JavaObject(null);
            }
            default -> {
            }
        }
        ClassInfo cls = this.resolveClass(creation.type(), this.pkg());
        if (cls == null) {
            this.fail("java.lang.ClassNotFoundException: 找不到类 " + creation.type() + this.location());
        }
        return this.instantiate(cls, args);
    }

    private Object evalUnary(JavaAst.Unary unary, Env env) {
        String op = unary.op();
        boolean postfix = unary.postfix();
        if (op.equals("++") || op.equals("--")) {
            Object old = this.eval(unary.expr(), env);
            Object delta = old instanceof Long ? (op.equals("++") ? 1L : -1L) : (op.equals("++") ? 1 : -1);
            Object updated = this.binary("+", old, delta);
            this.assignTo(unary.expr(), updated, env);
            return postfix ? old : updated;
        }
        Object value = this.eval(unary.expr(), env);
        return switch (op) {
            case "!" -> !this.toBool(value);
            case "-" -> this.negate(value);
            case "+" -> value;
            case "~" -> ~this.toInt(value);
            default -> value;
        };
    }

    private Object evalBinary(JavaAst.Binary binary, Env env) {
        String op = binary.op();
        if (op.equals("&&")) {
            if (!this.toBool(this.eval(binary.left(), env))) {
                return false;
            }
            return this.toBool(this.eval(binary.right(), env));
        }
        if (op.equals("||")) {
            if (this.toBool(this.eval(binary.left(), env))) {
                return true;
            }
            return this.toBool(this.eval(binary.right(), env));
        }
        Object left = this.eval(binary.left(), env);
        Object right = this.eval(binary.right(), env);
        return this.binary(op, left, right);
    }

    private Object evalAssign(JavaAst.Assign assign, Env env) {
        String op = assign.op();
        Object value;
        if (op.equals("=")) {
            value = this.eval(assign.value(), env);
        } else {
            Object old = this.eval(assign.target(), env);
            value = this.binary(op.substring(0, op.length() - 1), old, this.eval(assign.value(), env));
        }
        this.assignTo(assign.target(), value, env);
        return value;
    }

    private void assignTo(JavaAst.Expr target, Object value, Env env) {
        if (target instanceof JavaAst.Name name) {
            String type = env.typeOf(name.id());
            Object stored = coerce(value, type);
            if (env.set(name.id(), stored)) {
                return;
            }
            if (this.currentThis != null) {
                JavaAst.FieldDecl field = this.findField(this.currentThis.type, name.id());
                if (field != null && !field.isStatic()) {
                    this.currentThis.fields.put(name.id(), coerce(stored, field.type()));
                    return;
                }
            }
            if (this.currentClass != null) {
                for (ClassInfo cursor = this.currentClass; cursor != null; cursor = this.superOf(cursor)) {
                    for (JavaAst.FieldDecl field : cursor.decl.fields()) {
                        if (field.isStatic() && field.name().equals(name.id())) {
                            cursor.statics.put(name.id(), coerce(stored, field.type()));
                            return;
                        }
                    }
                }
            }
            env.define(name.id(), type, stored);
            return;
        }
        if (target instanceof JavaAst.Field field) {
            if (field.target() instanceof JavaAst.Name ref && env.get(ref.id()) == MISSING) {
                ClassInfo cls = this.resolveClass(ref.id(), this.pkg());
                if (cls != null) {
                    this.ensureInit(cls);
                    for (ClassInfo cursor = cls; cursor != null; cursor = this.superOf(cursor)) {
                        for (JavaAst.FieldDecl decl : cursor.decl.fields()) {
                            if (decl.isStatic() && decl.name().equals(field.name())) {
                                cursor.statics.put(field.name(), coerce(value, decl.type()));
                                return;
                            }
                        }
                    }
                }
            }
            Object object = this.eval(field.target(), env);
            if (object instanceof JavaObject instance) {
                JavaAst.FieldDecl decl = this.findField(instance.type, field.name());
                if (decl != null && !decl.isStatic()) {
                    instance.fields.put(field.name(), coerce(value, decl.type()));
                    return;
                }
            }
            this.fail("java.lang.NoSuchFieldError: " + field.name());
        }
        if (target instanceof JavaAst.Index index) {
            Object array = this.eval(index.array(), env);
            int position = this.toInt(this.eval(index.index(), env));
            if (array instanceof JavaArray values) {
                values.set(position, coerce(value, values.elementType));
                return;
            }
            if (array instanceof List<?> rawList) {
                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>) rawList;
                items.set(position, value);
                return;
            }
            this.fail("java.lang.RuntimeException: 不能对非数组使用下标赋值");
        }
        this.fail("java.lang.RuntimeException: 左边不是可赋值的变量");
    }

    // ------------------------------------------------------------------ 运算

    private Object binary(String op, Object left, Object right) {
        if (op.equals("+") && (left instanceof String || right instanceof String)) {
            return this.str(left) + this.str(right);
        }
        switch (op) {
            case "==" -> {
                return this.eq(left, right);
            }
            case "!=" -> {
                return !this.eq(left, right);
            }
            case "&" -> {
                if (left instanceof Boolean a && right instanceof Boolean b) {
                    return a & b;
                }
                return this.toLong(left) & this.toLong(right);
            }
            case "|" -> {
                if (left instanceof Boolean a && right instanceof Boolean b) {
                    return a | b;
                }
                return this.toLong(left) | this.toLong(right);
            }
            case "^" -> {
                if (left instanceof Boolean a && right instanceof Boolean b) {
                    return a ^ b;
                }
                return this.toLong(left) ^ this.toLong(right);
            }
            case "<<" -> {
                return this.toInt(left) << this.toInt(right);
            }
            case ">>" -> {
                return this.toInt(left) >> this.toInt(right);
            }
            case ">>>" -> {
                return this.toInt(left) >>> this.toInt(right);
            }
            default -> {
            }
        }
        if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
            if (left instanceof String a && right instanceof String b) {
                int compare = a.compareTo(b);
                return switch (op) {
                    case "<" -> compare < 0;
                    case ">" -> compare > 0;
                    case "<=" -> compare <= 0;
                    default -> compare >= 0;
                };
            }
            if (left instanceof Number && right instanceof Number) {
                double a = this.toDouble(left);
                double b = this.toDouble(right);
                return switch (op) {
                    case "<" -> a < b;
                    case ">" -> a > b;
                    case "<=" -> a <= b;
                    default -> a >= b;
                };
            }
            this.fail("java.lang.RuntimeException: 不能比较 " + describe(left) + " 和 " + describe(right));
        }
        boolean useDouble = left instanceof Double || right instanceof Double
                || left instanceof Float || right instanceof Float;
        boolean useLong = left instanceof Long || right instanceof Long;
        if (useDouble) {
            double a = this.toDouble(left);
            double b = this.toDouble(right);
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                case "%" -> a % b;
                default -> this.fail("未知运算符 " + op);
            };
        }
        if (useLong) {
            long a = this.toLong(left);
            long b = this.toLong(right);
            if ((op.equals("/") || op.equals("%")) && b == 0L) {
                this.fail("java.lang.ArithmeticException: / by zero");
            }
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                case "%" -> a % b;
                default -> this.fail("未知运算符 " + op);
            };
        }
        int a = this.toInt(left);
        int b = this.toInt(right);
        if ((op.equals("/") || op.equals("%")) && b == 0) {
            this.fail("java.lang.ArithmeticException: / by zero");
        }
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> a / b;
            case "%" -> a % b;
            default -> this.fail("未知运算符 " + op);
        };
    }

    private Object negate(Object value) {
        if (value instanceof Double) {
            return -this.toDouble(value);
        }
        if (value instanceof Long) {
            return -this.toLong(value);
        }
        return -this.toInt(value);
    }

    private boolean eq(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number a && right instanceof Number b) {
            if (left instanceof Double || right instanceof Double) {
                return a.doubleValue() == b.doubleValue();
            }
            return a.longValue() == b.longValue();
        }
        if (left instanceof String || right instanceof String) {
            return left.equals(right);
        }
        if (left instanceof Character a && right instanceof Character b) {
            return a.charValue() == b.charValue();
        }
        return left == right;
    }

    private Object cast(String type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (simpleName(type)) {
            case "int", "short", "byte" -> this.toInt(value);
            case "long" -> this.toLong(value);
            case "double", "float" -> this.toDouble(value);
            case "boolean" -> this.toBool(value);
            case "char" -> value instanceof Character character ? character : (char) this.toInt(value);
            case "String" -> this.str(value);
            default -> value;
        };
    }

    // ------------------------------------------------------------------ 内建库

    private Object printStream(String name, List<Object> args) {
        switch (name) {
            case "println" -> {
                if (args.isEmpty()) {
                    this.print("\n");
                } else {
                    this.print(this.str(args.get(0)) + "\n");
                }
                return null;
            }
            case "print" -> {
                if (!args.isEmpty()) {
                    this.print(this.str(args.get(0)));
                }
                return null;
            }
            case "printf", "format" -> {
                if (args.isEmpty()) {
                    return PRINT_STREAM;
                }
                Object[] rest = args.subList(1, args.size()).toArray();
                this.print(String.format(Locale.ROOT, this.str(args.get(0)), rest));
                return PRINT_STREAM;
            }
            default -> {
                this.fail("java.lang.NoSuchMethodError: System.out." + name);
                return null;
            }
        }
    }

    private void print(String text) {
        if (this.out.length() + text.length() > MAX_OUTPUT) {
            this.fail("输出超过 " + MAX_OUTPUT + " 字符上限，程序已中止");
        }
        this.out.append(text);
    }

    private Object stringCall(String text, String name, List<Object> args) {
        return switch (name) {
            case "length" -> text.length();
            case "isEmpty" -> text.isEmpty();
            case "isBlank" -> text.isBlank();
            case "charAt" -> text.charAt(this.toInt(args.get(0)));
            case "substring" -> args.size() > 1
                    ? text.substring(this.toInt(args.get(0)), this.toInt(args.get(1)))
                    : text.substring(this.toInt(args.get(0)));
            case "indexOf" -> args.get(0) instanceof Character character
                    ? text.indexOf(character) : text.indexOf(this.str(args.get(0)));
            case "lastIndexOf" -> args.get(0) instanceof Character character
                    ? text.lastIndexOf(character) : text.lastIndexOf(this.str(args.get(0)));
            case "equals" -> text.equals(this.str(args.get(0)));
            case "equalsIgnoreCase" -> text.equalsIgnoreCase(this.str(args.get(0)));
            case "contains" -> text.contains(this.str(args.get(0)));
            case "startsWith" -> text.startsWith(this.str(args.get(0)));
            case "endsWith" -> text.endsWith(this.str(args.get(0)));
            case "toUpperCase" -> text.toUpperCase(Locale.ROOT);
            case "toLowerCase" -> text.toLowerCase(Locale.ROOT);
            case "trim", "strip" -> text.trim();
            case "replace" -> text.replace(this.str(args.get(0)), this.str(args.get(1)));
            case "repeat" -> text.repeat(Math.max(0, this.toInt(args.get(0))));
            case "concat" -> text.concat(this.str(args.get(0)));
            case "compareTo" -> text.compareTo(this.str(args.get(0)));
            case "toCharArray" -> {
                JavaArray array = new JavaArray("char", text.length());
                for (int i = 0; i < text.length(); i++) {
                    array.values[i] = text.charAt(i);
                }
                yield array;
            }
            case "split" -> {
                String[] parts;
                try {
                    parts = text.split(this.str(args.get(0)));
                } catch (PatternSyntaxException exception) {
                    parts = text.split(java.util.regex.Pattern.quote(this.str(args.get(0))));
                }
                JavaArray array = new JavaArray("String", parts.length);
                for (int i = 0; i < parts.length; i++) {
                    array.values[i] = parts[i];
                }
                yield array;
            }
            case "chars" -> {
                yield text;
            }
            default -> {
                this.fail("java.lang.NoSuchMethodError: String." + name);
                yield null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Object listCall(List<?> list, String name, List<Object> args) {
        List<Object> items = (List<Object>) list;
        return switch (name) {
            case "size" -> list.size();
            case "isEmpty" -> list.isEmpty();
            case "get" -> list.get(this.toInt(args.get(0)));
            case "contains" -> list.contains(args.get(0));
            case "indexOf" -> list.indexOf(args.get(0));
            case "toString" -> list.toString();
            case "add" -> {
                if (items == null) {
                    this.fail("该列表不可修改");
                }
                if (args.size() == 1) {
                    items.add(args.get(0));
                    yield true;
                }
                int position = this.toInt(args.get(0));
                items.add(position, args.get(1));
                yield null;
            }
            case "set" -> {
                if (items == null) {
                    this.fail("该列表不可修改");
                }
                yield items.set(this.toInt(args.get(0)), args.get(1));
            }
            case "remove" -> {
                if (items == null) {
                    this.fail("该列表不可修改");
                }
                if (args.get(0) instanceof Integer position && position >= 0 && position < items.size()) {
                    yield items.remove((int) position);
                }
                items.remove(args.get(0));
                yield true;
            }
            case "clear" -> {
                if (items == null) {
                    this.fail("该列表不可修改");
                }
                items.clear();
                yield null;
            }
            case "addAll" -> {
                if (items == null) {
                    this.fail("该列表不可修改");
                }
                Object source = args.get(0);
                if (source instanceof List<?> other) {
                    items.addAll(other);
                } else if (source instanceof JavaArray array) {
                    items.addAll(Arrays.asList(array.values));
                }
                yield true;
            }
            default -> {
                this.fail("java.lang.NoSuchMethodError: List." + name);
                yield null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Object mapCall(Map<?, ?> map, String name, List<Object> args) {
        Map<Object, Object> items = (Map<Object, Object>) map;
        return switch (name) {
            case "size" -> map.size();
            case "isEmpty" -> map.isEmpty();
            case "get" -> map.get(args.get(0));
            case "getOrDefault" -> map.containsKey(args.get(0))
                    ? map.get(args.get(0)) : args.get(1);
            case "containsKey" -> map.containsKey(args.get(0));
            case "containsValue" -> map.containsValue(args.get(0));
            case "put" -> {
                if (items == null) {
                    this.fail("该映射不可修改");
                }
                yield items.put(args.get(0), args.get(1));
            }
            case "remove" -> {
                if (items == null) {
                    this.fail("该映射不可修改");
                }
                yield items.remove(args.get(0));
            }
            case "keySet" -> new ArrayList<>(map.keySet());
            case "values" -> new ArrayList<>(map.values());
            case "toString" -> map.toString();
            default -> {
                this.fail("java.lang.NoSuchMethodError: Map." + name);
                yield null;
            }
        };
    }

    private Object builderCall(StringBuilder builder, String name, List<Object> args) {
        return switch (name) {
            case "append" -> {
                builder.append(this.str(args.get(0)));
                yield builder;
            }
            case "toString" -> builder.toString();
            case "length" -> builder.length();
            case "reverse" -> builder.reverse();
            case "insert" -> {
                builder.insert(this.toInt(args.get(0)), this.str(args.get(1)));
                yield builder;
            }
            default -> {
                this.fail("java.lang.NoSuchMethodError: StringBuilder." + name);
                yield null;
            }
        };
    }

    private boolean isBuiltinStaticClass(String name) {
        return switch (name) {
            case "Math", "System", "Integer", "Long", "Double", "Float", "Short", "Byte",
                    "String", "Arrays", "Character", "Boolean", "List", "Collections" -> true;
            default -> false;
        };
    }

    private Object builtinStaticField(String className, String name) {
        switch (className) {
            case "System" -> {
                if (name.equals("out") || name.equals("err")) {
                    return PRINT_STREAM;
                }
            }
            case "Math" -> {
                if (name.equals("PI")) {
                    return Math.PI;
                }
                if (name.equals("E")) {
                    return Math.E;
                }
            }
            case "Integer" -> {
                if (name.equals("MAX_VALUE")) {
                    return Integer.MAX_VALUE;
                }
                if (name.equals("MIN_VALUE")) {
                    return Integer.MIN_VALUE;
                }
            }
            case "Long" -> {
                if (name.equals("MAX_VALUE")) {
                    return Long.MAX_VALUE;
                }
                if (name.equals("MIN_VALUE")) {
                    return Long.MIN_VALUE;
                }
            }
            case "Double" -> {
                if (name.equals("MAX_VALUE")) {
                    return Double.MAX_VALUE;
                }
                if (name.equals("MIN_VALUE")) {
                    return Double.MIN_VALUE;
                }
                if (name.equals("NaN")) {
                    return Double.NaN;
                }
            }
            case "Boolean" -> {
                if (name.equals("TRUE")) {
                    return Boolean.TRUE;
                }
                if (name.equals("FALSE")) {
                    return Boolean.FALSE;
                }
            }
            default -> {
            }
        }
        return MISSING;
    }

    private Object builtinStaticCall(String className, String name, List<Object> args) {
        switch (className) {
            case "Math" -> {
                return switch (name) {
                    case "abs" -> args.get(0) instanceof Double ? Math.abs(this.toDouble(args.get(0)))
                            : args.get(0) instanceof Long ? Math.abs(this.toLong(args.get(0)))
                            : Math.abs(this.toInt(args.get(0)));
                    case "max" -> this.binary(">", args.get(0), args.get(1)).equals(Boolean.TRUE)
                            ? args.get(0) : args.get(1);
                    case "min" -> this.binary("<", args.get(0), args.get(1)).equals(Boolean.TRUE)
                            ? args.get(0) : args.get(1);
                    case "pow" -> Math.pow(this.toDouble(args.get(0)), this.toDouble(args.get(1)));
                    case "sqrt" -> Math.sqrt(this.toDouble(args.get(0)));
                    case "cbrt" -> Math.cbrt(this.toDouble(args.get(0)));
                    case "round" -> Math.round(this.toDouble(args.get(0)));
                    case "floor" -> Math.floor(this.toDouble(args.get(0)));
                    case "ceil" -> Math.ceil(this.toDouble(args.get(0)));
                    case "random" -> Math.random();
                    case "signum" -> Math.signum(this.toDouble(args.get(0)));
                    case "log" -> Math.log(this.toDouble(args.get(0)));
                    case "log10" -> Math.log10(this.toDouble(args.get(0)));
                    case "exp" -> Math.exp(this.toDouble(args.get(0)));
                    case "toDegrees" -> Math.toDegrees(this.toDouble(args.get(0)));
                    case "toRadians" -> Math.toRadians(this.toDouble(args.get(0)));
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Math." + name);
                        yield null;
                    }
                };
            }
            case "System" -> {
                return switch (name) {
                    case "exit" -> {
                        throw new ExitSignal(args.isEmpty() ? 0 : this.toInt(args.get(0)));
                    }
                    case "currentTimeMillis" -> System.currentTimeMillis();
                    case "nanoTime" -> System.nanoTime();
                    case "getProperty" -> "HajikunOS";
                    case "lineSeparator" -> "\n";
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: System." + name);
                        yield null;
                    }
                };
            }
            case "Integer" -> {
                return switch (name) {
                    case "parseInt", "valueOf" -> this.toInt(args.get(0));
                    case "toString" -> this.str(args.get(0));
                    case "max" -> Math.max(this.toInt(args.get(0)), this.toInt(args.get(1)));
                    case "min" -> Math.min(this.toInt(args.get(0)), this.toInt(args.get(1)));
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Integer." + name);
                        yield null;
                    }
                };
            }
            case "Long" -> {
                return switch (name) {
                    case "parseLong", "valueOf" -> this.toLong(args.get(0));
                    case "toString" -> this.str(args.get(0));
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Long." + name);
                        yield null;
                    }
                };
            }
            case "Double", "Float" -> {
                return switch (name) {
                    case "parseDouble", "valueOf", "parseFloat" -> this.toDouble(args.get(0));
                    case "toString" -> this.str(args.get(0));
                    case "isNaN" -> args.get(0) instanceof Double d && d.isNaN();
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Double." + name);
                        yield null;
                    }
                };
            }
            case "Short", "Byte" -> {
                return this.toInt(args.get(0));
            }
            case "String" -> {
                return switch (name) {
                    case "valueOf" -> this.str(args.get(0));
                    case "format" -> {
                        Object[] rest = args.subList(1, args.size()).toArray();
                        yield String.format(Locale.ROOT, this.str(args.get(0)), rest);
                    }
                    case "join" -> {
                        String separator = this.str(args.get(0));
                        List<Object> parts = new ArrayList<>();
                        if (args.size() == 2 && args.get(1) instanceof List<?> list) {
                            parts.addAll(list);
                        } else if (args.size() == 2 && args.get(1) instanceof JavaArray array) {
                            parts.addAll(Arrays.asList(array.values));
                        } else {
                            parts.addAll(args.subList(1, args.size()));
                        }
                        StringBuilder builder = new StringBuilder();
                        for (int i = 0; i < parts.size(); i++) {
                            if (i > 0) {
                                builder.append(separator);
                            }
                            builder.append(this.str(parts.get(i)));
                        }
                        yield builder.toString();
                    }
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: String." + name);
                        yield null;
                    }
                };
            }
            case "Arrays" -> {
                return switch (name) {
                    case "toString" -> this.str(args.get(0));
                    case "sort" -> {
                        if (args.get(0) instanceof JavaArray array) {
                            Arrays.sort(array.values, JavaRunner::compareValues);
                        } else if (args.get(0) instanceof List<?> raw) {
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) raw;
                            list.sort(JavaRunner::compareValues);
                        }
                        yield null;
                    }
                    case "fill" -> {
                        if (args.get(0) instanceof JavaArray array) {
                            Arrays.fill(array.values, args.get(1));
                        }
                        yield null;
                    }
                    case "asList" -> new ArrayList<>(args);
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Arrays." + name);
                        yield null;
                    }
                };
            }
            case "Collections" -> {
                return switch (name) {
                    case "sort" -> {
                        if (args.get(0) instanceof List<?> raw) {
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) raw;
                            list.sort(JavaRunner::compareValues);
                        }
                        yield null;
                    }
                    case "reverse" -> {
                        if (args.get(0) instanceof List<?> raw) {
                            @SuppressWarnings("unchecked")
                            List<Object> list = (List<Object>) raw;
                            java.util.Collections.reverse(list);
                        }
                        yield null;
                    }
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Collections." + name);
                        yield null;
                    }
                };
            }
            case "Character" -> {
                Object first = args.isEmpty() ? null : args.get(0);
                char c = first instanceof Character character ? character
                        : first instanceof String text && !text.isEmpty() ? text.charAt(0) : '\0';
                return switch (name) {
                    case "isDigit", "isNumber" -> Character.isDigit(c);
                    case "isLetter" -> Character.isLetter(c);
                    case "isLetterOrDigit" -> Character.isLetterOrDigit(c);
                    case "isWhitespace" -> Character.isWhitespace(c);
                    case "isUpperCase" -> Character.isUpperCase(c);
                    case "isLowerCase" -> Character.isLowerCase(c);
                    case "toUpperCase" -> Character.toUpperCase(c);
                    case "toLowerCase" -> Character.toLowerCase(c);
                    case "toString" -> String.valueOf(c);
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Character." + name);
                        yield null;
                    }
                };
            }
            case "Boolean" -> {
                return switch (name) {
                    case "parseBoolean" -> Boolean.parseBoolean(this.str(args.get(0)));
                    case "toString" -> this.str(args.get(0));
                    default -> {
                        this.fail("java.lang.NoSuchMethodError: Boolean." + name);
                        yield null;
                    }
                };
            }
            case "List" -> {
                return new ArrayList<>(args);
            }
            default -> {
                this.fail("java.lang.NoSuchMethodError: " + className + "." + name);
                return null;
            }
        }
    }

    private static int compareValues(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    // ------------------------------------------------------------------ 转换与工具

    private Object arrayFromLiteral(String declaredType, List<JavaAst.Expr> values, Env env) {
        String element = elementType(declaredType);
        JavaArray array = new JavaArray(element, values.size());
        for (int i = 0; i < values.size(); i++) {
            JavaAst.Expr value = values.get(i);
            if (value instanceof JavaAst.New nested && nested.type().isEmpty() && nested.init() != null) {
                array.values[i] = this.arrayFromLiteral(element, nested.init(), env);
            } else {
                array.values[i] = this.eval(value, env);
            }
        }
        return array;
    }

    private Object coerce(Object value, String type) {
        if (type == null || type.isBlank()) {
            return value;
        }
        String raw = type;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        if (raw.endsWith("[]")) {
            return value;
        }
        String simple = simpleName(type);
        if (value == null) {
            return switch (simple) {
                case "int", "short", "byte" -> 0;
                case "long" -> 0L;
                case "double", "float" -> 0.0;
                case "boolean" -> false;
                case "char" -> '\0';
                default -> null;
            };
        }
        return switch (simple) {
            case "int", "short", "byte" -> this.toInt(value);
            case "long" -> this.toLong(value);
            case "double", "float" -> this.toDouble(value);
            case "boolean" -> this.toBool(value);
            case "char" -> value instanceof Character character ? character : (char) this.toInt(value);
            case "String" -> value instanceof String ? value : this.str(value);
            default -> value;
        };
    }

    private static Object defaultFor(String type) {
        if (type == null) {
            return null;
        }
        String simple = simpleName(type);
        if (type.endsWith("[]")) {
            return null;
        }
        return switch (simple) {
            case "int", "short", "byte" -> 0;
            case "long" -> 0L;
            case "double", "float" -> 0.0;
            case "boolean" -> false;
            case "char" -> '\0';
            case "void" -> null;
            default -> null;
        };
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException exception) {
                this.fail("java.lang.NumberFormatException: " + text);
            }
        }
        this.fail("java.lang.ClassCastException: 不能把 " + describe(value) + " 当成 int");
        return 0;
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof Boolean bool) {
            return bool ? 1L : 0L;
        }
        return this.toInt(value);
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                this.fail("java.lang.NumberFormatException: " + text);
            }
        }
        this.fail("java.lang.ClassCastException: 不能把 " + describe(value) + " 当成 double");
        return 0.0D;
    }

    private boolean toBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        this.fail("java.lang.ClassCastException: 需要 boolean，实际是 " + describe(value));
        return false;
    }

    private String str(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }
        if (value instanceof JavaArray array) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < array.values.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(this.str(array.values[i]));
            }
            return builder.append(']').toString();
        }
        if (value instanceof JavaObject object) {
            if (object.type != null) {
                JavaAst.MethodDecl method = this.findMethod(object.type, "toString", 0);
                if (method != null) {
                    Object text = this.invoke(object.type, object, method, List.of());
                    return text == null ? "null" : String.valueOf(text);
                }
                return object.type.simple + "@" + Integer.toHexString(System.identityHashCode(object));
            }
            return "java.lang.Object@" + Integer.toHexString(System.identityHashCode(object));
        }
        return String.valueOf(value);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Integer) {
            return "int";
        }
        if (value instanceof Long) {
            return "long";
        }
        if (value instanceof Double) {
            return "double";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Character) {
            return "char";
        }
        return value.getClass().getSimpleName();
    }

    private String location() {
        return this.currentClass == null ? "" : "（在 " + this.currentClass.simple + " 中）";
    }

    private String pkg() {
        return this.currentClass == null ? "" : this.currentClass.pkg;
    }

    private void step() {
        if (++this.steps > MAX_STEPS) {
            this.fail("java.lang.OutOfMemoryError: 程序执行步数超过 " + MAX_STEPS + " 步，已中止（疑似死循环）");
        }
    }

    private Object fail(String message) {
        throw new Failure(message);
    }

    /** 把 {@code a.b.C} 这样的字段链还原成限定名；不是纯名字链时返回 null。 */
    private static String qualifiedNameOf(JavaAst.Expr expr) {
        if (expr instanceof JavaAst.Name name) {
            return name.id();
        }
        if (expr instanceof JavaAst.Field field) {
            String base = qualifiedNameOf(field.target());
            return base == null ? null : base + "." + field.name();
        }
        return null;
    }

    private static String simpleName(String type) {
        if (type == null) {
            return "";
        }
        String name = type;
        int generic = name.indexOf('<');
        if (generic >= 0) {
            name = name.substring(0, generic);
        }
        while (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name;
    }

    private static String elementType(String type) {
        String arrayType = type == null ? "" : type;
        if (arrayType.endsWith("[]")) {
            arrayType = arrayType.substring(0, arrayType.length() - 2);
        }
        return arrayType.isBlank() ? "Object" : arrayType;
    }

    // ------------------------------------------------------------------ 数据结构

    private static final class ClassInfo {
        final String fullName;
        final String pkg;
        final String simple;
        final JavaAst.ClassDecl decl;
        final Map<String, Object> statics = new LinkedHashMap<>();
        boolean initStarted;

        ClassInfo(String fullName, String pkg, JavaAst.ClassDecl decl) {
            this.fullName = fullName;
            this.pkg = pkg == null ? "" : pkg;
            this.simple = decl.name();
            this.decl = decl;
        }
    }

    private static final class JavaObject {
        final ClassInfo type;
        final Map<String, Object> fields = new LinkedHashMap<>();

        JavaObject(ClassInfo type) {
            this.type = type;
        }
    }

    private static final class JavaArray {
        final String elementType;
        final Object[] values;

        JavaArray(String elementType, int size) {
            this.elementType = elementType == null || elementType.isBlank() ? "Object" : elementType;
            this.values = new Object[size];
        }

        JavaArray(String elementType, Object[] values) {
            this.elementType = elementType == null || elementType.isBlank() ? "Object" : elementType;
            this.values = values;
        }

        Object get(int index) {
            if (index < 0 || index >= this.values.length) {
                throw new Failure("java.lang.ArrayIndexOutOfBoundsException: 下标 " + index
                        + "，数组长度 " + this.values.length);
            }
            return this.values[index];
        }

        void set(int index, Object value) {
            if (index < 0 || index >= this.values.length) {
                throw new Failure("java.lang.ArrayIndexOutOfBoundsException: 下标 " + index
                        + "，数组长度 " + this.values.length);
            }
            this.values[index] = value;
        }
    }

    private static final class Env {
        final Env parent;
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();

        Env(Env parent) {
            this.parent = parent;
        }

        void define(String name, String type, Object value) {
            this.values.put(name, value);
            this.types.put(name, type);
        }

        Object get(String name) {
            for (Env cursor = this; cursor != null; cursor = cursor.parent) {
                if (cursor.values.containsKey(name)) {
                    return cursor.values.get(name);
                }
            }
            return MISSING;
        }

        String typeOf(String name) {
            for (Env cursor = this; cursor != null; cursor = cursor.parent) {
                if (cursor.types.containsKey(name)) {
                    return cursor.types.get(name);
                }
            }
            return null;
        }

        boolean set(String name, Object value) {
            for (Env cursor = this; cursor != null; cursor = cursor.parent) {
                if (cursor.values.containsKey(name)) {
                    cursor.values.put(name, value);
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ReturnSignal extends RuntimeException {
        final Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final class ExitSignal extends RuntimeException {
        final int code;

        ExitSignal(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    private static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message, null, false, false);
        }
    }
}
