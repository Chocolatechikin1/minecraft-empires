package com.devc.minecraftempires.client.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.devc.minecraftempires.network.ClientMapHandler;

public class MapScreen extends Screen {

    private final int panelWidth = 150; 

    public MapScreen() {
        super(Component.translatable("gui.minecraftempires.map_screen.title"));
    }

    @Override
    protected void init() {
        super.init();
        // this.mapWidget = new InteractiveMapWidget(0, 0, this.width - panelWidth, this.height);
        // this.addRenderableWidget(this.mapWidget);
    }

    // Modern 26.2 Signature: 'extractLabels' is the correct hook for drawing custom UI text and borders
    @Override
    protected void extractLabels(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) { //new
        super.extractLabels(guiGraphics, mouseX, mouseY); //new

        int panelX = this.width - panelWidth;
        
        guiGraphics.fill(panelX, 0, this.width, this.height, 0xDD111111);
        guiGraphics.fill(panelX, 0, panelX + 2, this.height, 0xFF888888); 

        guiGraphics.centeredText(this.font, Component.literal("Province Details"), panelX + (panelWidth / 2), 15, 0xFFD700);
        guiGraphics.fill(panelX + 10, 28, this.width - 10, 29, 0xFF555555); 

        int yOffset = 40;
        
        boolean hasData = ClientMapHandler.getInstance().getLatestMapData() != null;
        String statusText = hasData ? "Link: Online" : "Status: Awaiting Data...";
        int statusColor = hasData ? 0x00FF00 : 0xFF5555; 
        
        guiGraphics.text(this.font, Component.literal(statusText), panelX + 10, yOffset, statusColor, false); //new: added shadow boolean requirement from PDF
        
        yOffset += 15;
        int chunkCount = hasData ? ClientMapHandler.getInstance().getLatestMapData().chunks().size() : 0;
        guiGraphics.text(this.font, Component.literal("Chunks Loaded: " + chunkCount), panelX + 10, yOffset, 0xAAAAAA, false); //new: added shadow boolean requirement from PDF
    }

    @Override
    public boolean isPauseScreen() {
        return false; 
    }
}

/*package com.devc.minecraftempires.client.gui.screen;

//import graphics libraries
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.devc.minecraftempires.network.ClientMapHandler;

public class MapScreen extends Screen {

    // Dynamic width for the right-side control panel based on your mockup
    private final int panelWidth = 150; 

    public MapScreen() {
        // Modern 26.x Component translation for UI titles
        super(Component.translatable("gui.minecraftempires.map_screen.title"));
    }

    @Override
    protected void init() {
        super.init();
        
        // STEP 3: We will inject our InteractiveMapWidget right here!
        // int mapWidth = this.width - panelWidth;
        // this.mapWidget = new InteractiveMapWidget(0, 0, mapWidth, this.height);
        // this.addRenderableWidget(this.mapWidget);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Modern 26.2 Signature: 'renderBackground' is now 'extractBackground'
        super.extractBackground(guiGraphics, mouseX, mouseY, partialTick);

        // --- Render the Map Area (Left) ---
        // (This will be drawn automatically once we insert the Widget in Step 3)

        // --- Render the Details Panel (Right) ---
        int panelX = this.width - panelWidth;
        
        // Draw a darker, solid backing for the control panel to contrast with the map
        guiGraphics.fill(panelX, 0, this.width, this.height, 0xDD111111);

        // Draw a clean vertical border line separating the map and the panel
        guiGraphics.fill(panelX, 0, panelX + 2, this.height, 0xFF888888); 

        guiGraphics.centeredText(this.font, Component.literal("Province Details"), panelX + (panelWidth / 2), 15, 0xFFD700);

        // Draw an underline beneath the title
        guiGraphics.fill(panelX + 10, 28, this.width - 10, 29, 0xFF555555); 

        // Placeholder Data reading securely from our ClientMapHandler
        int yOffset = 40;
        
        boolean hasData = ClientMapHandler.getInstance().getLatestMapData() != null;
        String statusText = hasData ? "Link: Online" : "Status: Awaiting Data...";
        int statusColor = hasData ? 0x00FF00 : 0xFF5555; // Green if online, Red if awaiting
        
        guiGraphics.text(this.font, Component.literal(statusText), panelX + 10, yOffset, statusColor);
        
        yOffset += 15;
        int chunkCount = hasData ? ClientMapHandler.getInstance().getLatestMapData().chunks().size() : 0;
        guiGraphics.text(this.font, Component.literal("Chunks Loaded: " + chunkCount), panelX + 10, yOffset, 0xAAAAAA);

        // Render any standard widgets/buttons
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        // Returning false ensures the grand-strategy live-tick continues (e.g. troop movements, sieges)
        return false; 
    }
}*/