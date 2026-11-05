package com.akun.hajikun.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 迷你 JDK 的词法分析器。
 *
 * <p>只覆盖 Java 的常用语法子集，但注释、字符串转义、数字后缀、泛型尖括号
 * 都按真实 javac 的规则切分，因此从外面拷进来的普通 Java 源码可以直接读。</p>
 */
final class JavaLexer {
    enum Kind { IDENT, KEYWORD, NUMBER, STRING, CHAR, SYMBOL, EOF }

    record Token(Kind kind, String text, int line, int col) {
        boolean is(String value) {
            return this.text.equals(value);
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "var", "record", "sealed", "permits", "yield");

    private static final String[] SYMBOLS = {
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::",
            "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "<<", ">>",
            "(", ")", "{", "}", "[", "]", ";", ",", ".", "=", "+", "-", "*", "/", "%", "!",
            "~", "<", ">", "&", "|", "^", "?", ":", "@"
    };

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;
    private int line = 1;
    private int col = 1;

    private JavaLexer(String source) {
        this.source = source;
    }

    static List<Token> lex(String source) {
        JavaLexer lexer = new JavaLexer(source);
        lexer.scan();
        return lexer.tokens;
    }

    private void scan() {
        while (this.pos < this.source.length()) {
            char c = this.source.charAt(this.pos);
            if (c == '\n') {
                this.pos++;
                this.line++;
                this.col = 1;
                continue;
            }
            if (Character.isWhitespace(c)) {
                this.pos++;
                this.col++;
                continue;
            }
            if (c == '/' && this.peek(1) == '/') {
                while (this.pos < this.source.length() && this.source.charAt(this.pos) != '\n') {
                    this.pos++;
                    this.col++;
                }
                continue;
            }
            if (c == '/' && this.peek(1) == '*') {
                this.pos += 2;
                this.col += 2;
                while (this.pos < this.source.length()
                        && !(this.source.charAt(this.pos) == '*' && this.peek(1) == '/')) {
                    if (this.source.charAt(this.pos) == '\n') {
                        this.line++;
                        this.col = 1;
                    } else {
                        this.col++;
                    }
                    this.pos++;
                }
                this.pos = Math.min(this.source.length(), this.pos + 2);
                this.col += 2;
                continue;
            }
            int startLine = this.line;
            int startCol = this.col;
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(this.peek(1)))) {
                this.number(startLine, startCol);
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = this.pos;
                while (this.pos < this.source.length()
                        && Character.isJavaIdentifierPart(this.source.charAt(this.pos))) {
                    this.pos++;
                    this.col++;
                }
                String text = this.source.substring(start, this.pos);
                this.tokens.add(new Token(KEYWORDS.contains(text) ? Kind.KEYWORD : Kind.IDENT,
                        text, startLine, startCol));
            } else if (c == '"') {
                this.string(startLine, startCol);
            } else if (c == '\'') {
                this.character(startLine, startCol);
            } else {
                boolean matched = false;
                for (String symbol : SYMBOLS) {
                    if (this.source.startsWith(symbol, this.pos)) {
                        this.pos += symbol.length();
                        this.col += symbol.length();
                        this.tokens.add(new Token(Kind.SYMBOL, symbol, startLine, startCol));
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new JavaSyntaxException(startLine, startCol, "无法识别的字符 '" + c + "'");
                }
            }
        }
        this.tokens.add(new Token(Kind.EOF, "<文件结束>", this.line, this.col));
    }

    private char peek(int offset) {
        int index = this.pos + offset;
        return index < this.source.length() ? this.source.charAt(index) : '\0';
    }

    private void number(int startLine, int startCol) {
        int start = this.pos;
        if (this.source.charAt(this.pos) == '0' && (this.peek(1) == 'x' || this.peek(1) == 'X')) {
            this.pos += 2;
            this.col += 2;
            while (this.pos < this.source.length()
                    && (Character.digit(this.source.charAt(this.pos), 16) >= 0
                    || this.source.charAt(this.pos) == '_')) {
                this.pos++;
                this.col++;
            }
        } else {
            while (this.pos < this.source.length()
                    && (Character.isDigit(this.source.charAt(this.pos))
                    || this.source.charAt(this.pos) == '_')) {
                this.pos++;
                this.col++;
            }
            if (this.pos < this.source.length() && this.source.charAt(this.pos) == '.'
                    && Character.isDigit(this.peek(1))) {
                this.pos++;
                this.col++;
                while (this.pos < this.source.length()
                        && (Character.isDigit(this.source.charAt(this.pos))
                        || this.source.charAt(this.pos) == '_')) {
                    this.pos++;
                    this.col++;
                }
            }
            if (this.pos < this.source.length()
                    && (this.source.charAt(this.pos) == 'e' || this.source.charAt(this.pos) == 'E')) {
                this.pos++;
                this.col++;
                if (this.pos < this.source.length()
                        && (this.source.charAt(this.pos) == '+' || this.source.charAt(this.pos) == '-')) {
                    this.pos++;
                    this.col++;
                }
                while (this.pos < this.source.length() && Character.isDigit(this.source.charAt(this.pos))) {
                    this.pos++;
                    this.col++;
                }
            }
        }
        if (this.pos < this.source.length() && "lLfFdD".indexOf(this.source.charAt(this.pos)) >= 0) {
            this.pos++;
            this.col++;
        }
        this.tokens.add(new Token(Kind.NUMBER, this.source.substring(start, this.pos), startLine, startCol));
    }

    private void string(int startLine, int startCol) {
        this.pos++;
        this.col++;
        StringBuilder text = new StringBuilder();
        while (this.pos < this.source.length()) {
            char c = this.source.charAt(this.pos);
            if (c == '"') {
                this.pos++;
                this.col++;
                this.tokens.add(new Token(Kind.STRING, text.toString(), startLine, startCol));
                return;
            }
            if (c == '\n') {
                throw new JavaSyntaxException(startLine, startCol, "字符串没有结束的双引号");
            }
            if (c == '\\') {
                this.pos++;
                this.col++;
                text.append(this.escape());
            } else {
                text.append(c);
                this.pos++;
                this.col++;
            }
        }
        throw new JavaSyntaxException(startLine, startCol, "字符串没有结束的双引号");
    }

    private void character(int startLine, int startCol) {
        this.pos++;
        this.col++;
        if (this.pos >= this.source.length()) {
            throw new JavaSyntaxException(startLine, startCol, "字符字面量没有结束");
        }
        char value;
        if (this.source.charAt(this.pos) == '\\') {
            this.pos++;
            this.col++;
            value = this.escape();
        } else {
            value = this.source.charAt(this.pos);
            this.pos++;
            this.col++;
        }
        if (this.pos >= this.source.length() || this.source.charAt(this.pos) != '\'') {
            throw new JavaSyntaxException(startLine, startCol, "字符字面量只能包含一个字符");
        }
        this.pos++;
        this.col++;
        this.tokens.add(new Token(Kind.CHAR, String.valueOf(value), startLine, startCol));
    }

    private char escape() {
        char c = this.source.charAt(this.pos);
        this.pos++;
        this.col++;
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case '0' -> '\0';
            case '\\' -> '\\';
            case '\'' -> '\'';
            case '"' -> '"';
            case 'u' -> this.unicodeEscape();
            default -> c;
        };
    }

    private char unicodeEscape() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (this.pos >= this.source.length()) {
                throw new JavaSyntaxException(this.line, this.col, "\\u 转义不完整");
            }
            int digit = Character.digit(this.source.charAt(this.pos), 16);
            if (digit < 0) {
                throw new JavaSyntaxException(this.line, this.col, "\\u 转义需要四位十六进制");
            }
            value = value * 16 + digit;
            this.pos++;
            this.col++;
        }
        return (char) value;
    }
}
