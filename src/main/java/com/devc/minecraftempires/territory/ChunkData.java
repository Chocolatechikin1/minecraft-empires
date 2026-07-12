package com.devc.minecraftempires.territory;

//import dependencies
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public class ChunkData {
    private static final String OWNER_UUID_KEY = "OwnerUUID";
    private static final String SETTLEMENT_ID_KEY = "SettlementID";
    private static final String IS_GARRISONED_KEY = "IsGarrisoned";
    private static final String SETTLEMENT_TIER_KEY = "SettlementTier";

    private UUID ownerUUID;
    private String settlementID;
    private boolean isGarrisoned;
    private int settlementTier;
    
    //constructor
    public ChunkData(UUID ownerUUID, String settlementID, boolean isGarrisoned, int settlementTier) {
        this.ownerUUID = ownerUUID;
        this.settlementID = settlementID;
        this.isGarrisoned = isGarrisoned;
        this.settlementTier = settlementTier;
    }

    //save() NBT serialization method: converts object variables into NBT data to write to the disk
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        if(this.ownerUUID != null) {
            tag.putString(OWNER_UUID_KEY, this.ownerUUID.toString());
        }
        tag.putString(SETTLEMENT_ID_KEY, this.settlementID != null ? this.settlementID : "");
        tag.putBoolean(IS_GARRISONED_KEY, this.isGarrisoned);
        tag.putInt(SETTLEMENT_TIER_KEY, this.settlementTier);
        return tag;
    }

    //load() NBT deserialization method: converts NBT data read from the disk into ChunkData objects
    // load() NBT deserialization method
    public static ChunkData fromNBT(CompoundTag tag) {
        UUID owner = null;
        //check if key exists with contains()
        if (tag.contains(OWNER_UUID_KEY)) {
            //unwrap string
            String uuidStr = tag.getString(OWNER_UUID_KEY).orElse("");
            if (!uuidStr.isEmpty()) {
                owner = UUID.fromString(uuidStr);
            }
        }

        //extract settlement ID
        String settlement = tag.getString(SETTLEMENT_ID_KEY).orElse("");
        
        //extract garrisoned status and settlement tier
        boolean garrisoned = tag.getBoolean(IS_GARRISONED_KEY).orElse(false);
        int tier = tag.getInt(SETTLEMENT_TIER_KEY).orElse(0);

        return new ChunkData(owner, settlement, garrisoned, tier);
    }

    //getters and setters
    public UUID getOwnerUUID() {
        return ownerUUID;
    }
    public String getSettlementID() {
        return settlementID;
    }
    public boolean isGarrisoned() {
        return isGarrisoned;
    }
    public int getSettlementTier() {
        return settlementTier;
    }

    public void setOwnerUUID(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }
    public void setSettlementID(String settlementID) {
        this.settlementID = settlementID;
    }
    public void setIsGarrisoned(boolean isGarrisoned) {
        this.isGarrisoned = isGarrisoned;
    }
    public void setSettlementTier(int settlementTier) {
        this.settlementTier = settlementTier;
    }

}
