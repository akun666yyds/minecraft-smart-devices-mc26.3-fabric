package com.akun.hajikun.jvm;

/** 编译期语法错误：带行列号，由 javac 命令格式化成 javac 风格诊断。 */
final class JavaSyntaxException extends RuntimeException {
    private final int line;
    private final int col;

    JavaSyntaxException(int line, int col, String message) {
        super(message, null, false, false);
        this.line = line;
        this.col = col;
    }

    int line() {
        return this.line;
    }

    int col() {
        return this.col;
    }
}
