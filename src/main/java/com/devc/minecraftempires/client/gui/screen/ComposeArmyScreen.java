package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.client.map.ClientArmyData;
import com.devc.minecraftempires.network.packet.ArmyMapPayload;
import com.devc.minecraftempires.network.packet.ComposeArmyPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** good description ill keep it. Summary: the ui popup that appears when you click a legion and want to select specific cohorts to deploy to an army
 * STUB — Army Composition Screen.
 *
 * This screen will allow players to:
 *  1. Browse their available (undeployed) cohorts grouped by Legion.
 *  2. Select the cohorts they want to include in the new Army.
 *  3. Confirm composition — sends {@link ComposeArmyPayload} to the server.
 *
 * Design constraints:
 *  - Max 6 Legions worth of infantry (60 cohorts) + 5 Legions of cavalry (30 squadrons).
 *  - Each selected cohort must not be already deployed (isDeployed() == false on the server).
 *  - The Army spawns at the Legion's current position; if multiple Legions are selected the
 *    spawning position defaults to the first Legion in the list.
 *
 * CURRENTLY UNIMPLEMENTED — the button is rendered as a disabled placeholder.
 * Full implementation deferred to Sprint 7 UI pass.
 *
 * When implementing, wire the confirm button to:
 *   ClientPacketDistributor.sendToServer(new ComposeArmyPayload(selectedCohortIds, spawnPos));
 */
public class ComposeArmyScreen extends Screen {
    private final UUID sourceLegionId;

    public ComposeArmyScreen(UUID sourceLegionId) {
        super(Component.literal("Compose Army"));
        this.sourceLegionId = sourceLegionId;
    }

    @Override
    protected void init() {
        super.init();

        // Placeholder close button — full UI deferred to Sprint 7
        this.addRenderableWidget(Button.builder(Component.literal("Close (UI not yet implemented)"), b -> this.onClose()
        ).bounds(this.width / 2 - 80, this.height / 2 + 20, 160, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Background
        graphics.fill(cx - 150, cy - 80, cx + 150, cy + 60, 0xEE0D1218);
        graphics.fill(cx - 150, cy - 80, cx + 150, cy - 78, 0xFF657381);

        graphics.centeredText(this.font, Component.literal("Compose Army"), cx, cy - 70, 0xFFFFD45A);
        graphics.centeredText(this.font,
                Component.literal("§7[STUB] Select cohorts to include in the new army."),
                cx, cy - 50, 0xFFB0B8C0);
        graphics.centeredText(this.font,
                Component.literal("§7Full UI coming in Sprint 7."),
                cx, cy - 38, 0xFFB0B8C0);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /**
     * Convenience factory — composes an Army from ALL undeployed cohorts in the given Legion.
     * Sends the packet immediately without opening the screen.
     * Useful for auto-wrap testing; full UI will replace this flow.
     *
     * @param sourceLegionId the Legion whose cohorts to deploy
     * @param spawnPos       where on the map the Army should appear
     */
    public static void quickComposeFromLegion(UUID sourceLegionId, BlockPos spawnPos) {
        ClientArmyData.Snapshot snapshot = ClientArmyData.get();
        ArmyMapPayload.LegionSummary legion = snapshot.legionsById().get(sourceLegionId);
        if (legion == null) return;

        // NOTE: The client doesn't have full cohort-level data yet. The server
        // will auto-select all available cohorts when cohortIds is empty and
        // sourceLegionId is provided — this is handled in ArmyManager.autoWrapLegionInArmy().
        // For now we send an empty list; the server uses the Legion as context.
        List<UUID> cohortIds = new ArrayList<>();
        ClientPacketDistributor.sendToServer(new ComposeArmyPayload(cohortIds, spawnPos));
    }
}
