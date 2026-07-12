package com.devc.minecraftempires.territory;

//import dependencies
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.util.datafix.DataFixTypes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ClaimManager extends SavedData {
    //central data storage, HashMap for O(1) lookups, key is ChunkPos, value is ChunkData
    private final Map<ChunkPos, ChunkData> claims = new HashMap<>();

    //standard identifier
    private static final String DATA_NAME = "minecraftempires_claims";
    private static final String CLAIMS_LIST_KEY = "ClaimsList";
    private static final String CHUNK_POS_KEY = "ChunkPosLong";

    private static final Codec<ClaimManager> CODEC = CompoundTag.CODEC.xmap(ClaimManager::fromTag, ClaimManager::toTag);

    public static final SavedDataType<ClaimManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace(DATA_NAME),
        ClaimManager::new,
        CODEC,
        DataFixTypes.LEVEL
    );

    //constructor
    public ClaimManager() {
        //initialize the claims map
    }

    //access method to fetch or create new instance
    public static ClaimManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    //claim management methods
    //claim a chunk
    public void setClaim(ChunkPos pos, UUID ownerUUID, String settlementID, boolean isGarrisoned, int tier) {
        ChunkData data = new ChunkData(ownerUUID, settlementID, isGarrisoned, tier);
        claims.put(pos, data);
        setDirty(); //flag for  NeoForge that this data changed and MUST be saved to disk
    }

    //unclaim a chunk
    public void removeClaim(ChunkPos pos) {
        if (claims.remove(pos) != null) {
            setDirty();
        }
    }

    //retreive chunk data at a coordinate point, returns null if unclaimed
    public ChunkData getClaim(ChunkPos pos) {
        return claims.get(pos);
    }

    //method to check if chunk is claimed
    public boolean isClaimed(ChunkPos pos) {
        return claims.containsKey(pos);
    }

    //serialization
    private CompoundTag toTag() {
        ListTag list = new ListTag();

        for (Map.Entry<ChunkPos, ChunkData> entry : claims.entrySet()) {
            list.add(toEntryTag(entry.getKey(), entry.getValue()));
        }

        CompoundTag tag = new CompoundTag();
        tag.put(CLAIMS_LIST_KEY, list);
        return tag;
    }

    private static ClaimManager fromTag(CompoundTag tag) {
        ClaimManager manager = new ClaimManager();

        if (tag.contains(CLAIMS_LIST_KEY)) {
            ListTag list = tag.getList(CLAIMS_LIST_KEY).orElse(new ListTag());
            
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entryTag = list.getCompound(i).orElse(new CompoundTag());
                decodeEntryTag(entryTag).ifPresent(entry -> manager.claims.put(entry.pos(), entry.data()));
            }
        }

        return manager;
    }

    private static CompoundTag toEntryTag(ChunkPos pos, ChunkData data) {
        CompoundTag entryTag = data.toNBT();
        entryTag.putLong(CHUNK_POS_KEY, pos.pack());
        return entryTag;
    }

    private static java.util.Optional<ClaimEntry> decodeEntryTag(CompoundTag entryTag) {
        if (!entryTag.contains(CHUNK_POS_KEY)) {
            return java.util.Optional.empty();
        }

        long posLong = entryTag.getLong(CHUNK_POS_KEY).orElse(0L);
        ChunkPos pos = ChunkPos.unpack(posLong);
        ChunkData data = ChunkData.fromNBT(entryTag);
        return java.util.Optional.of(new ClaimEntry(pos, data));
    }

    private record ClaimEntry(ChunkPos pos, ChunkData data) {}
}
