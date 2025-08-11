package com.zihaomc.ghost.features.bedrockminer.enums;

public enum PowerBlockType {
    RedstoneTorch("redstone-torch"),
    Lever("lever"),
    Both("both");

    private final String name;

    PowerBlockType(String name) { this.name = name; }

    public static PowerBlockType of(boolean isRedstoneTorch, boolean isLever) {
        if (isRedstoneTorch) return isLever ? Both : RedstoneTorch;
        return isLever ? Lever : null;
    }

    public boolean isRedstoneTorch() { return this != Lever; }
    public boolean isLever() { return this != RedstoneTorch; }

    @Override public String toString() { return name; }
}