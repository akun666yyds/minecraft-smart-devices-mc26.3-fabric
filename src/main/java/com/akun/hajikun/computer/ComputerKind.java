package com.akun.hajikun.computer;

/** 电脑的两种形态：台式机与笔记本。 */
public enum ComputerKind {
    DESKTOP("desktop"),
    LAPTOP("laptop");

    private final String id;

    ComputerKind(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }
}
