package com.akun.hajikun.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 迷你 JDK 的语法分析器：递归下降，覆盖 Java 的常用语句与表达式。
 *
 * <p>不支持的部分（switch、lambda、try/catch、record、内部类）会在编译阶段
 * 给出明确的中文诊断，而不是在运行时莫名失败。</p>
 */
final class JavaParser {
    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");
    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "native",
            "synchronized", "strictfp", "transient", "volatile", "default", "sealed");

    private final List<JavaLexer.Token> tokens;
    private int index;

    private JavaParser(List<JavaLexer.Token> tokens) {
        this.tokens = tokens;
    }

    static JavaAst.Unit parse(String source) {
        return new JavaParser(JavaLexer.lex(source)).unit();
    }

    // ------------------------------------------------------------------ 顶层

    private JavaAst.Unit unit() {
        String pkg = "";
        List<JavaAst.ClassDecl> types = new ArrayList<>();
        while (!this.eof()) {
            this.skipAnnotations();
            if (this.check("package")) {
                this.advance();
                pkg = this.qualifiedName();
                this.expect(";");
                continue;
            }
            if (this.check("import")) {
                while (!this.check(";") && !this.eof()) {
                    this.advance();
                }
                this.expect(";");
                continue;
            }
            this.skipModifiers();
            if (this.check("class") || this.check("interface")) {
                types.add(this.classDecl());
                continue;
            }
            if (this.check("record")) {
                throw this.error("暂不支持 record，请改用普通 class");
            }
            if (this.check("enum")) {
                throw this.error("暂不支持 enum，请改用 class + static final 常量");
            }
            throw this.error("这里需要 class 或 interface");
        }
        if (types.isEmpty()) {
            throw new JavaSyntaxException(1, 1, "源文件里没有任何类");
        }
        return new JavaAst.Unit(pkg, types);
    }

    private JavaAst.ClassDecl classDecl() {
        boolean isInterface = this.check("interface");
        this.advance();
        String name = this.expectIdent();
        this.skipTypeParams();
        String superName = null;
        if (this.match("extends")) {
            superName = this.qualifiedName();
            this.skipTypeParams();
            if (isInterface) {
                while (this.match(",")) {
                    this.qualifiedName();
                    this.skipTypeParams();
                }
            }
        }
        if (this.match("implements")) {
            this.qualifiedName();
            this.skipTypeParams();
            while (this.match(",")) {
                this.qualifiedName();
                this.skipTypeParams();
            }
        }
        if (this.match("permits")) {
            this.qualifiedName();
            while (this.match(",")) {
                this.qualifiedName();
            }
        }
        this.expect("{");
        List<JavaAst.FieldDecl> fields = new ArrayList<>();
        List<JavaAst.MethodDecl> methods = new ArrayList<>();
        while (!this.check("}") && !this.eof()) {
            this.member(name, fields, methods);
        }
        this.expect("}");
        return new JavaAst.ClassDecl(name, isInterface, superName, fields, methods);
    }

    private void member(String className, List<JavaAst.FieldDecl> fields,
                        List<JavaAst.MethodDecl> methods) {
        this.skipAnnotations();
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean isFinal = false;
        while (this.peek().kind() == JavaLexer.Kind.KEYWORD && MODIFIERS.contains(this.peek().text())) {
            String modifier = this.advance().text();
            switch (modifier) {
                case "static" -> isStatic = true;
                case "abstract" -> isAbstract = true;
                case "final" -> isFinal = true;
                default -> {
                }
            }
        }
        this.skipAnnotations();
        if (this.check("{")) {
            // 初始化块：直接跳过，静态初始化在本实现里按字段初始化顺序展开。
            this.skipBalanced("{", "}");
            return;
        }
        if (this.check("class") || this.check("interface")) {
            throw this.error("暂不支持内部类，请把 " + this.peek(1).text() + " 放到单独的文件里");
        }
        if (this.check("<")) {
            throw this.error("暂不支持泛型方法");
        }
        if (this.peek().kind() == JavaLexer.Kind.IDENT && this.peek().text().equals(className)
                && this.peek(1).is("(")) {
            String name = this.advance().text();
            List<JavaAst.Param> params = this.params();
            this.skipThrows();
            if (this.check(";")) {
                throw this.error("构造函数必须有方法体");
            }
            JavaAst.Stmt body = this.block();
            methods.add(new JavaAst.MethodDecl(false, false, "void", name, params, body));
            return;
        }
        String type = this.type();
        String name = this.expectIdent();
        if (this.check("(")) {
            List<JavaAst.Param> params = this.params();
            this.skipThrows();
            JavaAst.Stmt body = null;
            if (this.match(";")) {
                body = null;
            } else {
                body = this.block();
            }
            methods.add(new JavaAst.MethodDecl(isStatic, isAbstract || body == null,
                    type, name, params, body));
            return;
        }
        if (this.check("[") && this.peek(1).is("]")) {
            this.advance();
            this.advance();
            type = type + "[]";
        }
        do {
            String fieldName = name;
            if (name == null) {
                fieldName = this.expectIdent();
            }
            JavaAst.Expr init = null;
            if (this.match("=")) {
                init = this.check("{") ? this.arrayLiteral() : this.expression();
            }
            fields.add(new JavaAst.FieldDecl(isStatic, isFinal, type, fieldName, init));
            name = null;
        } while (this.match(","));
        this.expect(";");
    }

    private List<JavaAst.Param> params() {
        this.expect("(");
        List<JavaAst.Param> params = new ArrayList<>();
        if (this.match(")")) {
            return params;
        }
        do {
            String type = this.type();
            if (this.match("...")) {
                type = type + "[]";
            }
            String name = this.expectIdent();
            if (this.check("[") && this.peek(1).is("]")) {
                this.advance();
                this.advance();
                type = type + "[]";
            }
            params.add(new JavaAst.Param(type, name));
        } while (this.match(","));
        this.expect(")");
        return params;
    }

    private void skipThrows() {
        if (!this.match("throws")) {
            return;
        }
        this.qualifiedName();
        while (this.match(",")) {
            this.qualifiedName();
        }
    }

    // ------------------------------------------------------------------ 语句

    private JavaAst.Stmt block() {
        this.expect("{");
        List<JavaAst.Stmt> statements = new ArrayList<>();
        while (!this.check("}") && !this.eof()) {
            statements.add(this.statement());
        }
        this.expect("}");
        return new JavaAst.Block(statements);
    }

    private void skipBalanced(String open, String close) {
        this.expect(open);
        int depth = 1;
        while (depth > 0 && !this.eof()) {
            if (this.check(open)) {
                depth++;
            } else if (this.check(close)) {
                depth--;
            }
            this.advance();
        }
    }

    private JavaAst.Stmt statement() {
        if (this.check("{")) {
            return this.block();
        }
        if (this.match(";")) {
            return new JavaAst.Empty();
        }
        if (this.check("if")) {
            this.advance();
            this.expect("(");
            JavaAst.Expr cond = this.expression();
            this.expect(")");
            JavaAst.Stmt then = this.statement();
            JavaAst.Stmt otherwise = this.match("else") ? this.statement() : null;
            return new JavaAst.If(cond, then, otherwise);
        }
        if (this.check("while")) {
            this.advance();
            this.expect("(");
            JavaAst.Expr cond = this.expression();
            this.expect(")");
            return new JavaAst.While(cond, this.statement());
        }
        if (this.check("do")) {
            this.advance();
            JavaAst.Stmt body = this.statement();
            this.expect("while");
            this.expect("(");
            JavaAst.Expr cond = this.expression();
            this.expect(")");
            this.expect(";");
            return new JavaAst.DoWhile(body, cond);
        }
        if (this.check("for")) {
            return this.forStatement();
        }
        if (this.check("return")) {
            this.advance();
            JavaAst.Expr value = this.check(";") ? null : this.expression();
            this.expect(";");
            return new JavaAst.Return(value);
        }
        if (this.check("break")) {
            this.advance();
            this.expect(";");
            return new JavaAst.Break();
        }
        if (this.check("continue")) {
            this.advance();
            this.expect(";");
            return new JavaAst.Continue();
        }
        if (this.check("switch")) {
            throw this.error("暂不支持 switch，请改用 if / else if");
        }
        if (this.check("try") || this.check("throw")) {
            throw this.error("暂不支持异常语法（try/catch/throw）");
        }
        if (this.check("synchronized")) {
            throw this.error("虚拟机是单线程的，不需要 synchronized");
        }
        // 基本类型开头必然是局部变量声明：直接解析，让语法错误报在真正的位置。
        if (this.startsPrimitiveDecl()) {
            List<JavaAst.Stmt> decls = this.varDecls();
            this.expect(";");
            return decls.size() == 1 ? decls.get(0) : new JavaAst.Block(decls);
        }
        int save = this.index;
        try {
            List<JavaAst.Stmt> decls = this.varDecls();
            if (this.match(";")) {
                return decls.size() == 1 ? decls.get(0) : new JavaAst.Block(decls);
            }
        } catch (JavaSyntaxException ignored) {
            // 不是变量声明，按表达式语句继续解析。
        }
        this.index = save;
        JavaAst.Expr expr = this.expression();
        this.expect(";");
        return new JavaAst.ExprStmt(expr);
    }

    private static final Set<String> LOCAL_TYPES = Set.of(
            "int", "long", "double", "float", "boolean", "char", "byte", "short");

    private boolean startsPrimitiveDecl() {
        JavaLexer.Token token = this.peek();
        if (token.kind() != JavaLexer.Kind.KEYWORD || !LOCAL_TYPES.contains(token.text())) {
            return false;
        }
        JavaLexer.Token next = this.peek(1);
        return next.kind() == JavaLexer.Kind.IDENT || (next.is("[") && this.peek(2).is("]"));
    }

    private List<JavaAst.Stmt> varDecls() {
        String type = this.type();
        List<JavaAst.Stmt> list = new ArrayList<>();
        do {
            String name = this.expectIdent();
            if (this.check("[") && this.peek(1).is("]")) {
                this.advance();
                this.advance();
                type = type + "[]";
            }
            JavaAst.Expr init = null;
            if (this.match("=")) {
                init = this.check("{") ? this.arrayLiteral() : this.expression();
            }
            list.add(new JavaAst.VarDecl(type, name, init));
        } while (this.match(","));
        return list;
    }

    private JavaAst.Stmt forStatement() {
        this.expect("for");
        this.expect("(");
        int save = this.index;
        try {
            String type = this.type();
            if (this.peek().kind() == JavaLexer.Kind.IDENT && this.peek(1).is(":")) {
                String name = this.advance().text();
                this.expect(":");
                JavaAst.Expr iterable = this.expression();
                this.expect(")");
                return new JavaAst.ForEach(type, name, iterable, this.statement());
            }
        } catch (JavaSyntaxException ignored) {
            // 回退到普通 for。
        }
        this.index = save;
        JavaAst.Stmt init = null;
        if (!this.check(";")) {
            int initSave = this.index;
            try {
                List<JavaAst.Stmt> decls = this.varDecls();
                init = decls.size() == 1 ? decls.get(0) : new JavaAst.Block(decls);
            } catch (JavaSyntaxException ignored) {
                this.index = initSave;
                List<JavaAst.Expr> exprs = new ArrayList<>();
                exprs.add(this.expression());
                while (this.match(",")) {
                    exprs.add(this.expression());
                }
                List<JavaAst.Stmt> statements = new ArrayList<>();
                for (JavaAst.Expr expr : exprs) {
                    statements.add(new JavaAst.ExprStmt(expr));
                }
                init = new JavaAst.Block(statements);
            }
        }
        this.expect(";");
        JavaAst.Expr cond = this.check(";") ? null : this.expression();
        this.expect(";");
        List<JavaAst.Expr> update = new ArrayList<>();
        if (!this.check(")")) {
            update.add(this.expression());
            while (this.match(",")) {
                update.add(this.expression());
            }
        }
        this.expect(")");
        return new JavaAst.For(init, cond, update, this.statement());
    }

    // ------------------------------------------------------------------ 表达式

    private JavaAst.Expr expression() {
        return this.assignment();
    }

    private JavaAst.Expr assignment() {
        JavaAst.Expr left = this.ternary();
        if (this.check("=") || this.check("+=") || this.check("-=") || this.check("*=")
                || this.check("/=") || this.check("%=") || this.check("&=") || this.check("|=")
                || this.check("^=") || this.check("<<=") || this.check(">>=")) {
            String op = this.advance().text();
            return new JavaAst.Assign(op, left, this.assignment());
        }
        return left;
    }

    private JavaAst.Expr ternary() {
        JavaAst.Expr cond = this.logicalOr();
        if (this.match("?")) {
            JavaAst.Expr yes = this.expression();
            this.expect(":");
            JavaAst.Expr no = this.expression();
            return new JavaAst.Ternary(cond, yes, no);
        }
        return cond;
    }

    private JavaAst.Expr logicalOr() {
        return this.chain(this::logicalAnd, "||");
    }

    private JavaAst.Expr logicalAnd() {
        return this.chain(this::bitOr, "&&");
    }

    private JavaAst.Expr bitOr() {
        return this.chain(this::bitXor, "|");
    }

    private JavaAst.Expr bitXor() {
        return this.chain(this::bitAnd, "^");
    }

    private JavaAst.Expr bitAnd() {
        return this.chain(this::equality, "&");
    }

    private JavaAst.Expr equality() {
        return this.chain(this::relational, "==", "!=");
    }

    private JavaAst.Expr relational() {
        return this.chain(this::shift, "<", ">", "<=", ">=");
    }

    private JavaAst.Expr shift() {
        return this.chain(this::additive, "<<", ">>", ">>>");
    }

    private JavaAst.Expr additive() {
        return this.chain(this::multiplicative, "+", "-");
    }

    private JavaAst.Expr multiplicative() {
        return this.chain(this::unary, "*", "/", "%");
    }

    private JavaAst.Expr chain(Supplier<JavaAst.Expr> next, String... operators) {
        JavaAst.Expr left = next.get();
        outer:
        while (true) {
            for (String operator : operators) {
                if (this.check(operator)) {
                    this.advance();
                    left = new JavaAst.Binary(operator, left, next.get());
                    continue outer;
                }
            }
            return left;
        }
    }

    private JavaAst.Expr unary() {
        if (this.check("!") || this.check("-") || this.check("+") || this.check("~")) {
            String op = this.advance().text();
            return new JavaAst.Unary(op, this.unary(), false);
        }
        if (this.check("++") || this.check("--")) {
            String op = this.advance().text();
            return new JavaAst.Unary(op, this.unary(), false);
        }
        if (this.check("(") && this.looksLikeCast()) {
            this.advance();
            String type = this.type();
            this.expect(")");
            return new JavaAst.Cast(type, this.unary());
        }
        return this.postfix();
    }

    private boolean looksLikeCast() {
        int save = this.index;
        try {
            this.advance();
            String type = this.type();
            if (!this.check(")")) {
                return false;
            }
            JavaLexer.Token next = this.peek(1);
            boolean primary = next.kind() == JavaLexer.Kind.IDENT
                    || next.kind() == JavaLexer.Kind.NUMBER
                    || next.kind() == JavaLexer.Kind.STRING
                    || next.kind() == JavaLexer.Kind.CHAR
                    || next.is("(") || next.is("new") || next.is("this") || next.is("super")
                    || next.is("!") || next.is("-") || next.is("+") || next.is("~");
            return primary;
        } catch (JavaSyntaxException exception) {
            return false;
        } finally {
            this.index = save;
        }
    }

    private JavaAst.Expr postfix() {
        JavaAst.Expr expr = this.primary();
        while (true) {
            if ((expr instanceof JavaAst.This || expr instanceof JavaAst.Super) && this.check("(")) {
                expr = new JavaAst.Call(expr, "<init>", this.arguments());
                continue;
            }
            if (this.check(".")) {
                this.advance();
                if (this.check("class")) {
                    throw this.error("暂不支持 .class 语法");
                }
                String name = this.expectIdent();
                if (this.check("(")) {
                    expr = new JavaAst.Call(expr, name, this.arguments());
                } else {
                    expr = new JavaAst.Field(expr, name);
                }
                continue;
            }
            if (this.match("[")) {
                JavaAst.Expr index = this.expression();
                this.expect("]");
                expr = new JavaAst.Index(expr, index);
                continue;
            }
            if (this.check("++") || this.check("--")) {
                String op = this.advance().text();
                expr = new JavaAst.Unary(op, expr, true);
                continue;
            }
            if (this.check("::")) {
                throw this.error("暂不支持方法引用（::）和 lambda");
            }
            return expr;
        }
    }

    private List<JavaAst.Expr> arguments() {
        this.expect("(");
        List<JavaAst.Expr> args = new ArrayList<>();
        if (this.match(")")) {
            return args;
        }
        args.add(this.expression());
        while (this.match(",")) {
            args.add(this.expression());
        }
        this.expect(")");
        return args;
    }

    private JavaAst.Expr primary() {
        JavaLexer.Token token = this.peek();
        if (token.kind() == JavaLexer.Kind.NUMBER) {
            this.advance();
            return new JavaAst.Literal(number(token.text()));
        }
        if (token.kind() == JavaLexer.Kind.STRING) {
            this.advance();
            return new JavaAst.Literal(token.text());
        }
        if (token.kind() == JavaLexer.Kind.CHAR) {
            this.advance();
            return new JavaAst.Literal(token.text().charAt(0));
        }
        if (token.is("true") || token.is("false")) {
            this.advance();
            return new JavaAst.Literal(Boolean.parseBoolean(token.text()));
        }
        if (token.is("null")) {
            this.advance();
            return new JavaAst.Literal(null);
        }
        if (token.is("this")) {
            this.advance();
            return new JavaAst.This();
        }
        if (token.is("super")) {
            this.advance();
            return new JavaAst.Super();
        }
        if (token.is("new")) {
            this.advance();
            return this.newExpression();
        }
        if (token.is("(")) {
            this.advance();
            JavaAst.Expr expr = this.expression();
            this.expect(")");
            return expr;
        }
        if (token.kind() == JavaLexer.Kind.IDENT) {
            this.advance();
            if (this.check("(")) {
                return new JavaAst.Call(null, token.text(), this.arguments());
            }
            return new JavaAst.Name(token.text());
        }
        throw this.error("这里需要一个表达式，实际读到 '" + token.text() + "'");
    }

    private JavaAst.Expr newExpression() {
        String type = this.type();
        // new int[]{1, 2}：type() 已经把尾部的中括号吃掉了。
        if (type.endsWith("[]") && this.check("{")) {
            return new JavaAst.New(type, List.of(), null, this.arrayLiteralValues());
        }
        if (this.check("[")) {
            this.advance();
            if (this.match("]")) {
                String arrayType = type + "[]";
                if (!this.check("{")) {
                    throw this.error("数组创建需要初始化列表，例如 new int[]{1, 2}");
                }
                return new JavaAst.New(arrayType, List.of(), null, this.arrayLiteralValues());
            }
            JavaAst.Expr size = this.expression();
            this.expect("]");
            while (this.match("[")) {
                if (!this.check("]")) {
                    this.expression();
                }
                this.expect("]");
            }
            return new JavaAst.New(type + "[]", List.of(), size, null);
        }
        this.expect("(");
        List<JavaAst.Expr> args = new ArrayList<>();
        if (!this.check(")")) {
            args.add(this.expression());
            while (this.match(",")) {
                args.add(this.expression());
            }
        }
        this.expect(")");
        return new JavaAst.New(type, args, null, null);
    }

    private JavaAst.Expr arrayLiteral() {
        return new JavaAst.New("", List.of(), null, this.arrayLiteralValues());
    }

    private List<JavaAst.Expr> arrayLiteralValues() {
        this.expect("{");
        List<JavaAst.Expr> values = new ArrayList<>();
        if (this.match("}")) {
            return values;
        }
        do {
            values.add(this.check("{") ? this.arrayLiteral() : this.expression());
        } while (this.match(","));
        this.expect("}");
        return values;
    }

    // ------------------------------------------------------------------ 类型与名字

    private String type() {
        String base;
        if (this.peek().kind() == JavaLexer.Kind.KEYWORD && PRIMITIVES.contains(this.peek().text())) {
            base = this.advance().text();
        } else {
            base = this.qualifiedName();
        }
        if (this.check("<")) {
            this.advance();
            int depth = 1;
            while (depth > 0 && !this.eof()) {
                if (this.check("<")) {
                    depth++;
                } else if (this.check(">")) {
                    depth--;
                } else if (this.check(">>")) {
                    depth -= 2;
                }
                this.advance();
            }
        }
        StringBuilder full = new StringBuilder(base);
        while (this.check("[") && this.peek(1).is("]")) {
            this.advance();
            this.advance();
            full.append("[]");
        }
        return full.toString();
    }

    private String qualifiedName() {
        StringBuilder name = new StringBuilder(this.expectIdent());
        while (this.check(".") && this.peek(1).kind() == JavaLexer.Kind.IDENT) {
            this.advance();
            name.append('.').append(this.expectIdent());
        }
        return name.toString();
    }

    private void skipTypeParams() {
        if (!this.check("<")) {
            return;
        }
        this.advance();
        int depth = 1;
        while (depth > 0 && !this.eof()) {
            if (this.check("<")) {
                depth++;
            } else if (this.check(">")) {
                depth--;
            } else if (this.check(">>")) {
                depth -= 2;
            }
            this.advance();
        }
    }

    private void skipModifiers() {
        while (this.peek().kind() == JavaLexer.Kind.KEYWORD && MODIFIERS.contains(this.peek().text())) {
            this.advance();
        }
    }

    private void skipAnnotations() {
        while (this.check("@")) {
            this.advance();
            while (this.peek().kind() == JavaLexer.Kind.KEYWORD && this.peek().text().equals("interface")) {
                this.advance();
            }
            this.qualifiedName();
            if (this.check("(")) {
                this.skipBalanced("(", ")");
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Object number(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean isDouble = lower.contains(".") || lower.contains("e")
                || lower.endsWith("f") || lower.endsWith("d");
        String digits = text.replace("_", "");
        if (digits.endsWith("f") || digits.endsWith("F") || digits.endsWith("d") || digits.endsWith("D")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        if (isDouble) {
            return Double.parseDouble(digits);
        }
        boolean isLong = lower.endsWith("l");
        if (isLong) {
            digits = digits.substring(0, digits.length() - 1);
        }
        long value;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            value = Long.parseLong(digits.substring(2), 16);
        } else {
            value = Long.parseLong(digits);
        }
        if (!isLong && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return (int) value;
        }
        return value;
    }

    private JavaLexer.Token peek() {
        return this.tokens.get(Math.min(this.index, this.tokens.size() - 1));
    }

    private JavaLexer.Token peek(int offset) {
        return this.tokens.get(Math.min(this.index + offset, this.tokens.size() - 1));
    }

    private boolean eof() {
        return this.peek().kind() == JavaLexer.Kind.EOF;
    }

    private JavaLexer.Token advance() {
        JavaLexer.Token token = this.peek();
        if (this.index < this.tokens.size() - 1) {
            this.index++;
        }
        return token;
    }

    private boolean check(String text) {
        return this.peek().text().equals(text);
    }

    private boolean match(String text) {
        if (this.check(text)) {
            this.advance();
            return true;
        }
        return false;
    }

    private void expect(String text) {
        if (!this.match(text)) {
            throw this.error("需要 '" + text + "'，实际读到 '" + this.peek().text() + "'");
        }
    }

    private String expectIdent() {
        if (this.peek().kind() != JavaLexer.Kind.IDENT) {
            throw this.error("需要标识符，实际读到 '" + this.peek().text() + "'");
        }
        return this.advance().text();
    }

    private JavaSyntaxException error(String message) {
        return new JavaSyntaxException(this.peek().line(), this.peek().col(), message);
    }
}
