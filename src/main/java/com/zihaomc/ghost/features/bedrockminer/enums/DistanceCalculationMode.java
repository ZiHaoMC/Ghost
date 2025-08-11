package com.zihaomc.ghost.features.bedrockminer.enums;

public enum DistanceCalculationMode {
    Old("old"),
    V1_19("1.19"),
    V1_20_6("1.20.6");

    private final String name;

    DistanceCalculationMode(String name) {
        this.name = name;
    }

    // 1.8.9: 移除了条件编译，直接硬编码为 Old，因为这是 1.8.9 使用的模式
    public static final DistanceCalculationMode currentClientVersion = Old;

    public static DistanceCalculationMode of(String name) {
        if (name == null)
            throw new NullPointerException("Name is null");
        switch (name) {
            case "old":
                return Old;
            case "1.19":
                return V1_19;
            case "1.20.6":
                return V1_20_6;
        }
        throw new IllegalArgumentException(
                "No enum constant " + DistanceCalculationMode.class.getCanonicalName() + "." + name);
    }

    @Override
    public String toString() {
        return name;
    }
}