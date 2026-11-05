package com.akun.hajikun.tv;

import net.minecraft.util.StringRepresentable;

/** 三种多格电视：宽 × 高（以方块计）。 */
public enum TvKind implements StringRepresentable {
    SMALL("tv_2x1", 2, 1),
    MEDIUM("tv_5x3", 5, 3),
    LARGE("tv_16x9", 16, 9);

    private final String id;
    private final int width;
    private final int height;

    TvKind(String id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    @Override
    public String getSerializedName() {
        return this.id;
    }

    public String id() {
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public String label() {
        return this.width + "×" + this.height;
    }

    public static TvKind byId(String id) {
        for (TvKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        return MEDIUM;
    }
}
