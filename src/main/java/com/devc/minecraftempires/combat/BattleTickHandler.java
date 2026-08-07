package com.devc.minecraftempires.combat;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

//network tick handler class, this is where the battle engine is ticked every 5 server ticks and registers all active BattleSession
//acts as a "bridge" between the combat class (BattleManager) and the server tick event system
public final class BattleTickHandler {
    //runs the tick every 5 server ticks, which is every 0.25 seconds (20 ticks per second) (can be adjusted for performance if needed)
    private static final int TICK_INTERVAL = 5;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % TICK_INTERVAL != 0) return;

        event.getServer().getAllLevels().forEach(level ->
                BattleManager.get(level).tick(level)
        );
    }
}
