package com.akun.hajikun.jvm;

import java.util.List;

/** 迷你 JDK 的抽象语法树：只有解释器真正会用到的节点。 */
final class JavaAst {
    private JavaAst() {
    }

    interface Expr {
    }

    interface Stmt {
    }

    record Unit(String pkg, List<ClassDecl> types) {
    }

    record ClassDecl(String name, boolean isInterface, String superName,
                     List<FieldDecl> fields, List<MethodDecl> methods) {
    }

    record FieldDecl(boolean isStatic, boolean isFinal, String type, String name, Expr init) {
    }

    record MethodDecl(boolean isStatic, boolean isAbstract, String returnType, String name,
                      List<Param> params, Stmt body) {
    }

    record Param(String type, String name) {
    }

    record Literal(Object value) implements Expr {
    }

    record Name(String id) implements Expr {
    }

    record This() implements Expr {
    }

    record Super() implements Expr {
    }

    record Field(Expr target, String name) implements Expr {
    }

    record Call(Expr target, String name, List<Expr> args) implements Expr {
    }

    record New(String type, List<Expr> args, Expr arraySize, List<Expr> init) implements Expr {
    }

    record Index(Expr array, Expr index) implements Expr {
    }

    record Unary(String op, Expr expr, boolean postfix) implements Expr {
    }

    record Binary(String op, Expr left, Expr right) implements Expr {
    }

    record Assign(String op, Expr target, Expr value) implements Expr {
    }

    record Ternary(Expr cond, Expr yes, Expr no) implements Expr {
    }

    record Cast(String type, Expr expr) implements Expr {
    }

    record Block(List<Stmt> statements) implements Stmt {
    }

    record VarDecl(String type, String name, Expr init) implements Stmt {
    }

    record ExprStmt(Expr expr) implements Stmt {
    }

    record If(Expr cond, Stmt then, Stmt otherwise) implements Stmt {
    }

    record While(Expr cond, Stmt body) implements Stmt {
    }

    record DoWhile(Stmt body, Expr cond) implements Stmt {
    }

    record For(Stmt init, Expr cond, List<Expr> update, Stmt body) implements Stmt {
    }

    record ForEach(String type, String name, Expr iterable, Stmt body) implements Stmt {
    }

    record Return(Expr value) implements Stmt {
    }

    record Break() implements Stmt {
    }

    record Continue() implements Stmt {
    }

    record Empty() implements Stmt {
    }
}
