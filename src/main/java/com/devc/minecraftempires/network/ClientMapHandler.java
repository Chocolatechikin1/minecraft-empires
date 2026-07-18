package com.devc.minecraftempires.network;

import com.devc.minecraftempires.network.packet.MapDataPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientMapHandler {
    
    private static ClientMapHandler INSTANCE;
    
    //store fetched chunk data for the map UI to read and render
    private MapDataPayload lastReceivedData;

    //singleton pattern for the client map handler
    public static ClientMapHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClientMapHandler();
        }
        return INSTANCE;
    }

    //payload handler
    public void handleData(MapDataPayload payload, IPayloadContext context) {
        //use of an enqueue to ensure that the data is processed on the main thread
        context.enqueueWork(() -> {
            this.lastReceivedData = payload;
            
            //ONCE UI IS BUILT, ADD IT HERE
        });
    }

    public MapDataPayload getLatestMapData() {
        return lastReceivedData;
    }
}