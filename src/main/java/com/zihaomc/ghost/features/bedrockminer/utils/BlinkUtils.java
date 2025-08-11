package com.zihaomc.ghost.features.bedrockminer.utils;

import net.minecraft.network.NetworkManager;
import java.util.concurrent.atomic.AtomicReference;

public final class BlinkUtils {
    private BlinkUtils() {}
    public static final AtomicReference<NetworkManager> blinkingConnection = new AtomicReference<>();

    public static boolean tryStartBlinking(NetworkManager connection) {
        return blinkingConnection.compareAndSet(null, connection);
    }
    public static boolean tryStopBlinking(NetworkManager connection) {
        final boolean result = blinkingConnection.compareAndSet(connection, null);
        if (connection != null) {
            connection.processReceivedPackets();
        }
        return result;
    }
}