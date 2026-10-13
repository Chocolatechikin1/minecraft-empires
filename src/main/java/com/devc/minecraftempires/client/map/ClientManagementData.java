package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.ManagementDataPayload;

public final class ClientManagementData {
    private static ManagementDataPayload snapshot = ManagementDataPayload.empty();
    private static int version;
    private ClientManagementData() {}
    public static ManagementDataPayload get() { return snapshot; }
    public static int version() { return version; }
    public static void accept(ManagementDataPayload value) { snapshot = value; version++; }
    public static void clear() { accept(ManagementDataPayload.empty()); }
}
