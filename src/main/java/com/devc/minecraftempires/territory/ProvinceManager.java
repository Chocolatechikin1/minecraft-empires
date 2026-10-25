package com.devc.minecraftempires.territory;

import com.devc.minecraftempires.army.ArmyManager;
import com.devc.minecraftempires.army.Cohort;
import com.devc.minecraftempires.army.Legion;
import com.devc.minecraftempires.network.packet.ProvincePayload;
import com.devc.minecraftempires.state.StateData;
import com.devc.minecraftempires.state.StateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Administrative data belongs to StateManager's save. This service never grants ownership by editing. */
/*
Province management function
Handles the creation, editing, and observation of provinces within a state. 
The ProvinceManager class provides methods to manage provinces, including creating new provinces, editing existing ones, merging provinces, and applying changes.
 It also tracks the ownership of chunks within provinces and handles the observation of provinces by players.
*/
public final class ProvinceManager {
    public enum Kind { PROVINCE, COMMANDERY, UNORGANIZED }
    public static final int MAX_PER_STATE=128, MAX_EDIT_CELLS=4096;
    public static final class Division {
        public final UUID id;
        public UUID owner, capital, conqueror; //conquerer is just to store provinces captured by another state
        public String name;
        public Kind kind=Kind.PROVINCE;
        private Map<Long,BitSet> lossBoundary=new LinkedHashMap<>();
        private final Map<UUID,BlockPos> releasedGarrisons=new LinkedHashMap<>();
        private Division(UUID id,UUID owner,String name,UUID capital){this.id=id;this.owner=owner;this.name=StateManager.cleanName(name);this.capital=capital;}
        public CompoundTag toNBT(){CompoundTag t=new CompoundTag();t.putString("Id",id.toString());t.putString("Owner",owner.toString());t.putString("Name",name);t.putString("Kind",kind.name());if(capital!=null)t.putString("Capital",capital.toString());if(conqueror!=null)t.putString("Conqueror",conqueror.toString());t.put("LossBoundary",saveMasks(lossBoundary));ListTag g=new ListTag();releasedGarrisons.forEach((id,pos)->{CompoundTag c=new CompoundTag();c.putString("Id",id.toString());c.putLong("Position",pos.asLong());g.add(c);});t.put("ReleasedGarrisons",g);return t;}
        public static Division fromNBT(CompoundTag t){Division p=new Division(UUID.fromString(t.getString("Id").orElseThrow()),UUID.fromString(t.getString("Owner").orElseThrow()),t.getString("Name").orElse("Province"),optional(t,"Capital"));p.kind=Kind.valueOf(t.getString("Kind").orElse("PROVINCE"));p.conqueror=optional(t,"Conqueror");p.lossBoundary=readMasks(t.getListOrEmpty("LossBoundary"));for(var value:t.getListOrEmpty("ReleasedGarrisons")){CompoundTag c=(CompoundTag)value;p.releasedGarrisons.put(UUID.fromString(c.getString("Id").orElseThrow()),BlockPos.of(c.getLong("Position").orElseThrow()));}return p;}
    }
    private final StateManager states;
    private final Map<UUID,Division> divisions=new LinkedHashMap<>();
    private final Map<UUID,UUID> defaults=new LinkedHashMap<>();
    // Only explicit exceptions are saved. Unassigned owned cells belong to the nation's wrapper.
    private final Map<Long,Map<UUID,BitSet>> membership=new LinkedHashMap<>();
    private final Map<UUID,LinkedHashMap<Long,ListTag>> knowledge=new HashMap<>();
    private final Map<UUID,Long> breaches=new HashMap<>();
    private final Map<UUID,Draft> drafts=new HashMap<>();
    private final LinkedHashMap<UUID,View> views=new LinkedHashMap<>(8,.75f,true);
    private long revision=1;
    private int conquestCursor;
    private static final class Draft {
        UUID owner,target;long revision,expires;Division division;boolean remove;
        final Map<Long,BitSet> mask=new LinkedHashMap<>();
        Iterator<Map.Entry<Long,BitSet>> suggestions;
        Map.Entry<Long,BitSet> current;int bit;
    }
    private record View(long topology,long revision,Map<UUID,Map<Long,BitSet>> masks){}
    public ProvinceManager(StateManager states){this.states=states;}
    public long revision(){return revision;}
    public Division get(UUID id){return divisions.get(id);}
    public List<Division> forState(UUID owner){return divisions.values().stream().filter(p->p.owner.equals(owner)).sorted(Comparator.comparing(p->p.id)).toList();}
    public UUID defaultProvince(UUID owner){
        UUID current=defaults.get(owner);Division existing=divisions.get(current);
        if(existing!=null&&existing.owner.equals(owner))return current;
        StateData nation=states.getState(owner);if(nation==null)return null;
        UUID id=stable("province:"+owner);if(divisions.containsKey(id))id=stable("province:"+owner+":"+revision);
        divisions.put(id,new Division(id,owner,StateManager.cleanName(nation.getStateName()+" Province"),nation.getCapitalSettlementId()));defaults.put(owner,id);changed();return id;
    }
    //returns province id for the given owner and chunk position, or the default province if none is found
    public UUID provinceAt(UUID owner,ChunkPos pos){
        for(var entry:membership.getOrDefault(ClaimManager.regionKey(pos),Map.of()).entrySet()){
            Division p=divisions.get(entry.getKey());if(p!=null&&p.owner.equals(owner)&&entry.getValue().get(ClaimManager.cellBit(pos)))return p.id;
        }
        return defaultProvince(owner);
    }
    private void changed(){revision=Math.incrementExact(revision);views.clear();states.setDirty();}
    //reconcile provinces with settlements and capitals, ensuring that each settlement is assigned to the correct province and that the capital of each province is valid
    public void reconcile(ClaimManager claims){
        for(StateData nation:states.getAllStates())defaultProvince(nation.getStateId());
        for(SettlementData site:states.getAllSettlements())if(!site.isIndependent()){
            UUID affiliation=provinceAt(site.getOwningStateId(),ChunkPos.containing(site.getCenterAltarPos()));
            if(!Objects.equals(affiliation,site.getSubdivisionId())){site.setSubdivisionId(affiliation);states.setDirty();}
        }
        for(Division p:divisions.values()){
            if(p.conqueror!=null)continue;
            SettlementData capital=states.getSettlement(p.capital);
            if(capital!=null&&p.owner.equals(capital.getOwningStateId())&&p.id.equals(capital.getSubdivisionId()))continue;
            StateData nation=states.getState(p.owner);UUID next=null;
            if(nation!=null&&p.id.equals(defaults.get(p.owner))){SettlementData seat=states.getSettlement(nation.getCapitalSettlementId());if(seat!=null&&p.id.equals(seat.getSubdivisionId()))next=seat.getSettlementId();}
            if(next==null)next=states.getAllSettlements().stream().filter(s->p.owner.equals(s.getOwningStateId())&&p.id.equals(s.getSubdivisionId())).map(SettlementData::getSettlementId).sorted().findFirst().orElse(null);
            if(!Objects.equals(next,p.capital)){p.capital=next;changed();}
        }
    }

    //note: these masks are for either distant lands or states that players dont interact with directly, this is done to save on bandwidth and processing time, as players only need to see the provinces they own or are interacting with
    //"masks" (hides) the province boundaries for a given owner and claim manager, returning a map of province IDs to their corresponding region masks
    private Map<UUID,Map<Long,BitSet>> masks(UUID owner,ClaimManager claims){
        View cached=views.get(owner);if(cached!=null&&cached.topology()==claims.getTopologyRevision()&&cached.revision()==revision)return cached.masks();
        Map<UUID,Map<Long,BitSet>> result=new LinkedHashMap<>();UUID wrapper=defaultProvince(owner);if(wrapper==null)return result;
        claims.ownedRegionMasks(owner).forEach((key,owned)->{
            BitSet rest=(BitSet)owned.clone();
            for(var entry:membership.getOrDefault(key,Map.of()).entrySet()){
                Division p=divisions.get(entry.getKey());if(p==null||!owner.equals(p.owner))continue;
                BitSet part=(BitSet)entry.getValue().clone();part.and(rest);rest.andNot(part);
                if(!part.isEmpty())result.computeIfAbsent(p.id,ignored->new LinkedHashMap<>()).put(key,part);
            }
            if(!rest.isEmpty())result.computeIfAbsent(wrapper,ignored->new LinkedHashMap<>()).put(key,rest);
        });
        views.put(owner,new View(claims.getTopologyRevision(),revision,result));while(views.size()>8)views.remove(views.keySet().iterator().next());return result;
    }
    //returns the mask for the specified province, or an empty map if the province is not found
    public Map<Long,BitSet> provinceMask(UUID id,ClaimManager claims){Division p=get(id);return p==null?Map.of():masks(p.owner,claims).getOrDefault(id,Map.of());}
    //returns a BitSet representing the land mask for the specified owner and province, based on the strategic regions and claims
    public BitSet regionalCaptureMask(UUID owner,UUID province,long key,ClaimManager claims){
        var region=claims.getStrategicRegions().get(key);BitSet mask=region==null?new BitSet():BitSet.valueOf(region.forOwner(owner).landMask());
        for(int bit=mask.nextSetBit(0);bit>=0;bit=mask.nextSetBit(bit+1))if(!province.equals(provinceAt(owner,ClaimManager.cellPosition(key,bit))))mask.clear(bit);return mask;
    }
    private static boolean contains(Map<Long,BitSet> mask,BlockPos position){ChunkPos pos=ChunkPos.containing(position);BitSet bits=mask.get(ClaimManager.regionKey(pos));return bits!=null&&bits.get(ClaimManager.cellBit(pos));}
    public void recordBreach(UUID defender,ChunkPos position,long now){UUID id=provinceAt(defender,position);if(id!=null)breaches.put(id,now);}
    //returns true if the specified owner has any field forces (soldiers or cohorts) present within the boundaries of the specified province, based on the provided ArmyManager and ClaimManager
    public boolean hasFieldForces(UUID owner,UUID province,ArmyManager armies,ClaimManager claims){
        Division division=get(province);Map<Long,BitSet> boundary=division!=null&&!division.lossBoundary.isEmpty()?division.lossBoundary:provinceMask(province,claims);
        for(var force:armies.getStrategicForcesForState(owner))if(force.soldiers()>0&&contains(boundary,force.position()))return true;
        for(Cohort cohort:armies.getAllCohorts()){
            Legion legion=armies.getParentLegion(cohort.getCohortId());
            if(legion!=null&&owner.equals(legion.getOwningStateId())&&cohort.isAlive()&&!cohort.isGarrisoned()
                    &&(!cohort.hasStoredPosition()||contains(boundary,cohort.getStoredPosition()))
                    &&!(division!=null&&cohort.getStoredPosition().equals(division.releasedGarrisons.get(cohort.getCohortId()))&&cohort.getWaypoints().isEmpty()))return true;
        }
        return false;
    }
    /** Called before ordinary capital capture releases garrisons. A prepared journal owns the full transfer. */
    //returns true if the capital capture is valid and initiates the province transfer process, false otherwise
    public boolean onCapitalCapture(ServerLevel level,SettlementData site,UUID attacker){
        ClaimManager claims=ClaimManager.get(level);reconcile(claims);Division p=get(provinceAt(site.getOwningStateId(),ChunkPos.containing(site.getCenterAltarPos())));
        if(p==null||p.kind!=Kind.PROVINCE||!site.getSettlementId().equals(p.capital)
                ||!contains(provinceMask(p.id,claims),site.getCenterAltarPos()))return false;
        if(p.conqueror==null){provinceMask(p.id,claims).forEach((key,bits)->p.lossBoundary.put(key,(BitSet)bits.clone()));
            for(Cohort cohort:ArmyManager.get(level).getAllCohorts())if(cohort.isGarrisoned()&&site.getSettlementId().equals(cohort.getGarrisonedSettlementId()))p.releasedGarrisons.put(cohort.getCohortId(),cohort.getStoredPosition());}
        p.conqueror=attacker;changed();
        return !hasFieldForces(p.owner,p.id,ArmyManager.get(level),claims)&&states.getPlayerConflicts().beginProvinceTransfer(level,p.id,true);
    }
    //returns true if there is a pending capital transfer for the specified site, false otherwise
    public boolean pendingCapitalTransfer(ServerLevel level,SettlementData site){Division p=get(provinceAt(site.getOwningStateId(),ChunkPos.containing(site.getCenterAltarPos())));return p!=null&&p.conqueror!=null&&site.getSettlementId().equals(p.capital)&&!hasFieldForces(p.owner,p.id,ArmyManager.get(level),ClaimManager.get(level));}
    public void tick(ServerLevel level){
        ClaimManager claims=ClaimManager.get(level);
        for(var entry:new ArrayList<>(drafts.entrySet())){
            Draft d=entry.getValue();if(d.expires<level.getGameTime()||d.revision!=revision||states.getState(d.owner)==null){drafts.remove(entry.getKey());continue;}
            advanceSuggestion(d);
        }
        if(level.getGameTime()%20!=0)return;
        reconcile(claims);breaches.values().removeIf(time->time<level.getGameTime()-2400);
        List<Division> candidates=divisions.values().stream().filter(p->p.conqueror!=null).toList();if(candidates.isEmpty())return;
        Division p=candidates.get(Math.floorMod(conquestCursor++,candidates.size()));SettlementData capital=states.getSettlement(p.capital);
        if(capital==null||p.owner.equals(capital.getOwningStateId())){p.conqueror=null;p.lossBoundary.clear();p.releasedGarrisons.clear();changed();return;}
        if(!p.conqueror.equals(capital.getOwningStateId())){p.conqueror=capital.getOwningStateId();changed();}
        if(!hasFieldForces(p.owner,p.id,ArmyManager.get(level),claims))states.getPlayerConflicts().beginProvinceTransfer(level,p.id);
    }

    //suggestion method for drafting province boundaries, iterates through the suggestions and updates the draft's mask based on the proximity of the capital and other settlements
    //note: it kinda doesnt work atm, review later (nonessential feature)
    private void advanceSuggestion(Draft d){
        if(d.suggestions==null)return;long deadline=System.nanoTime()+1_000_000L;int work=0;
        SettlementData capital=states.getSettlement(d.division.capital);if(capital==null){d.suggestions=null;return;}
        List<SettlementData> others=forState(d.owner).stream().filter(p->!p.id.equals(d.target)).map(p->states.getSettlement(p.capital)).filter(Objects::nonNull).toList();
        while(work++<512&&System.nanoTime()<deadline){
            if(d.current==null){
                if(!d.suggestions.hasNext()){d.suggestions=null;return;}d.current=d.suggestions.next();
                ChunkPos region=ChunkPos.unpack(d.current.getKey());int cx=region.x()*1024,cz=region.z()*1024;
                int wins=0;for(int dz:new int[]{0,1023})for(int dx:new int[]{0,1023}){BlockPos corner=new BlockPos(cx+dx,0,cz+dz);double dist=distance(corner,capital.getCenterAltarPos());if(others.stream().allMatch(s->dist<distance(corner,s.getCenterAltarPos())))wins++;}
                // Distance differences are affine: a unanimous rectangle can be accepted or rejected as one unit.
                if(wins==4){d.mask.put(d.current.getKey(),(BitSet)d.current.getValue().clone());d.current=null;continue;}
                boolean excluded=false;for(SettlementData other:others){int losses=0;for(int dz:new int[]{0,1023})for(int dx:new int[]{0,1023}){BlockPos corner=new BlockPos(cx+dx,0,cz+dz);if(distance(corner,other.getCenterAltarPos())<distance(corner,capital.getCenterAltarPos()))losses++;}if(losses==4){excluded=true;break;}}
                if(excluded){d.current=null;continue;}d.bit=d.current.getValue().nextSetBit(0);
            }
            if(d.bit<0){d.current=null;continue;}
            ChunkPos cell=ClaimManager.cellPosition(d.current.getKey(),d.bit);BlockPos at=new BlockPos(cell.x()*16+8,0,cell.z()*16+8);
            double distance=distance(at,capital.getCenterAltarPos());
            boolean nearest=others.stream().allMatch(other->distance<distance(at,other.getCenterAltarPos())||distance==distance(at,other.getCenterAltarPos())&&capital.getSettlementId().compareTo(other.getSettlementId())<0);
            if(nearest)d.mask.computeIfAbsent(d.current.getKey(),ignored->new BitSet()).set(d.bit);
            d.bit=d.current.getValue().nextSetBit(d.bit+1);
        }
    }
    private static double distance(BlockPos a,BlockPos b){double dx=(double)a.getX()-b.getX(),dz=(double)a.getZ()-b.getZ();return dx*dx+dz*dz;}
    /** Only leaders can edit; every commit rechecks the saved revision and current ownership. */
    /*
     * This method handles the actions related to province management.
        * It takes a ServerPlayer and a ProvincePayload.Action as parameters and performs the corresponding action based on the provided input.
        * The method checks if the player is the leader of the state and if there are any pending conquests or handovers before allowing province edits.
        * It supports actions such as creating a new province, editing an existing province, suggesting boundaries, applying changes, and merging provinces.
        * The method also handles area selection, name changes, capital changes, and kind changes for provinces.
        * The method returns a string message indicating the result of the action, such as success or error messages. 
    */
    public String action(ServerPlayer player,ProvincePayload.Action a){
        StateData nation=states.getStateByPlayer(player.getUUID());if(nation==null||!player.getUUID().equals(nation.getLeaderId()))return "Only the state's leader can edit provinces.";
        UUID owner=nation.getStateId();ClaimManager claims=ClaimManager.get(player.level());reconcile(claims);
        if(states.getAIWars().blocks(owner)||forState(owner).stream().anyMatch(p->p.conqueror!=null))return "Resolve the pending conquest or handover before editing provinces.";
        if(a.action().equals("CANCEL")){drafts.remove(player.getUUID());return "Province draft cancelled.";}
        if(a.revision()!=revision)return "Province boundaries changed. Refresh and try again.";
        Draft d=drafts.get(player.getUUID());Division selected=get(a.province());
        switch(a.action()){
            case "NEW","EDIT","SUGGEST" -> {
                if(a.action().equals("NEW")&&forState(owner).size()>=MAX_PER_STATE)return "This state has reached its province limit.";
                if(!a.action().equals("NEW")&&(selected==null||!owner.equals(selected.owner)))return "Select one of your provinces.";
                SettlementData capital=states.getSettlement(a.capital());
                if(a.action().equals("NEW")&&(capital==null||!owner.equals(capital.getOwningStateId())||forState(owner).stream().anyMatch(p->capital.getSettlementId().equals(p.capital))))return "Choose an owned settlement that is not already a provincial capital.";
                d=new Draft();d.owner=owner;d.revision=revision;d.expires=player.level().getGameTime()+2400;
                d.division=a.action().equals("NEW")?new Division(UUID.randomUUID(),owner,a.name().isBlank()?capital.getSettlementName():a.name(),capital.getSettlementId()):Division.fromNBT(selected.toNBT());
                d.target=d.division.id;
                for(var part:provinceMask(d.target,claims).entrySet())d.mask.put(part.getKey(),(BitSet)part.getValue().clone());
                if(!a.action().equals("EDIT")){d.mask.clear();d.suggestions=claims.ownedRegionMasks(owner).entrySet().iterator();}
                drafts.put(player.getUUID(),d);return a.action().equals("EDIT")?"Edit draft: mark an area, then apply or cancel.":"Preparing suggested boundaries. You can adjust them when ready.";
            }
            case "AREA" -> {
                if(d==null||d.suggestions!=null)return "Prepare a province draft first.";
                if(a.name().equals("REMOVE")&&d.target.equals(defaultProvince(owner)))return "Assign this area to another province; the national province holds unassigned land.";
                int minX=Math.min(a.x(),a.endX()),maxX=Math.max(a.x(),a.endX()),minZ=Math.min(a.z(),a.endZ()),maxZ=Math.max(a.z(),a.endZ());
                if((long)(maxX-minX+1)*(maxZ-minZ+1)>MAX_EDIT_CELLS)return "Select at most 4,096 chunks per correction.";
                for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
                    ChunkPos pos=new ChunkPos(x,z);if(!owner.equals(claims.getStrategicOwner(pos)))continue;
                    // A correction cannot move another province's capital out of that province.
                    UUID target=d.target;
                    boolean protectedCapital=forState(owner).stream().anyMatch(p->!p.id.equals(target)&&states.getSettlement(p.capital)!=null&&ChunkPos.containing(states.getSettlement(p.capital).getCenterAltarPos()).equals(pos));
                    if(protectedCapital)continue;
                    BitSet bits=d.mask.computeIfAbsent(ClaimManager.regionKey(pos),ignored->new BitSet());
                    if(a.name().equals("REMOVE"))bits.clear(ClaimManager.cellBit(pos));else bits.set(ClaimManager.cellBit(pos));
                }return "Correction preview updated. Apply to save it.";
            }
            case "NAME","CAPITAL","KIND" -> {
                if(d==null)return "Edit a province first.";
                if(a.action().equals("NAME")){String name=StateManager.cleanName(a.name());if(name.isBlank())return "Enter a province name.";d.division.name=name;}
                else if(a.action().equals("KIND")){
                    // Alternative territorial-loss rules belong to the later political systems.
                    if(!a.name().equals(Kind.PROVINCE.name()))return "Commandery and unorganized-territory conversion is not available yet.";
                    d.division.kind=Kind.PROVINCE;
                }
                else{SettlementData site=states.getSettlement(a.capital());if(site==null||!owner.equals(site.getOwningStateId())||!contains(d.mask,site.getCenterAltarPos()))return "The capital must be an owned settlement inside this draft.";d.division.capital=site.getSettlementId();}
                return "Province draft updated.";
            }
            case "MERGE" -> {
                if(selected==null||!owner.equals(selected.owner)||selected.id.equals(defaultProvince(owner)))return "Select a non-default province to merge into the national province.";
                d=new Draft();d.owner=owner;d.target=selected.id;d.division=Division.fromNBT(selected.toNBT());d.revision=revision;d.expires=player.level().getGameTime()+2400;d.remove=true;drafts.put(player.getUUID(),d);return "Preview: this province will merge into the national province.";
            }
            case "APPLY" -> {
                if(d==null||d.suggestions!=null)return "Wait for the province suggestion to finish.";
                if(d.revision!=revision)return "The draft is stale. Start a new edit.";
                SettlementData seat=states.getSettlement(d.division.capital);
                if(!d.remove&&d.division.kind==Kind.PROVINCE&&(seat==null||!owner.equals(seat.getOwningStateId())||!contains(d.mask,seat.getCenterAltarPos())))return "A province needs an owned capital within its boundaries.";
                Map<Long,BitSet> owned=claims.ownedRegionMasks(owner);for(var entry:d.mask.entrySet()){BitSet outside=(BitSet)entry.getValue().clone();outside.andNot(owned.getOrDefault(entry.getKey(),new BitSet()));if(!outside.isEmpty())return "Ownership changed inside the draft. Start a new edit.";}
                for(var parts:membership.values())parts.remove(d.target);
                if(d.remove)divisions.remove(d.target);else{
                    divisions.put(d.target,d.division);for(var entry:d.mask.entrySet())assign(d.target,entry.getKey(),entry.getValue());
                }
                membership.values().removeIf(Map::isEmpty);drafts.remove(player.getUUID());changed();reconcile(claims);return "Province boundaries saved.";
            }
            default -> {return "Unknown province action.";}
        }
    }

    //assigns the specified bits to the given province for the specified region key, updating the membership map accordingly
    private void assign(UUID province,long key,BitSet bits){
        Division target=get(province);if(target==null||bits.isEmpty())return;
        Map<UUID,BitSet> parts=membership.computeIfAbsent(key,ignored->new LinkedHashMap<>());
        parts.forEach((id,mask)->{Division old=get(id);if(old!=null&&old.owner.equals(target.owner))mask.andNot(bits);});parts.values().removeIf(BitSet::isEmpty);
        parts.put(province,(BitSet)bits.clone());
    }
    public void clearPlayer(UUID player){drafts.remove(player);}
    //removes all data associated with the specified player, including drafts, provinces, and other related information
    public void retire(UUID owner){Set<UUID> removed=new HashSet<>();forState(owner).forEach(p->removed.add(p.id));divisions.keySet().removeAll(removed);membership.values().forEach(parts->parts.keySet().removeAll(removed));membership.values().removeIf(Map::isEmpty);defaults.remove(owner);knowledge.remove(owner);drafts.values().removeIf(d->d.owner.equals(owner));changed();}
    /** Aggregate captures retain the same provincial trigger without realizing troops or terrain. */
    //for distant regions, this method updates the province data based on the outcome of a macro capture event, ensuring that the ownership and boundaries of provinces are correctly updated according to the captured settlements
    public void beforeMacroCapture(com.devc.minecraftempires.state.AIWarManager.Outcome outcome,ClaimManager claims){
        if(outcome.kind()!=com.devc.minecraftempires.state.AIWarManager.Kind.CAPTURE)return;
        for(var tag:outcome.settlements()){
            SettlementData next=SettlementData.fromNBT(tag),old=states.getSettlement(next.getSettlementId());
            if(old==null||old.getOwningStateId().equals(next.getOwningStateId()))continue;
            Division p=get(provinceAt(old.getOwningStateId(),ChunkPos.containing(old.getCenterAltarPos())));if(p==null||p.kind!=Kind.PROVINCE||!old.getSettlementId().equals(p.capital))continue;
            if(p.conqueror==null){provinceMask(p.id,claims).forEach((key,bits)->p.lossBoundary.put(key,(BitSet)bits.clone()));
                for(var change:outcome.regions())if(change.owner().equals(p.owner)){
                    var region=claims.getStrategicRegions().get(change.key());if(region==null)continue;BitSet captured=BitSet.valueOf(change.mask().length==0?region.landMask():change.mask());
                    for(int bit=captured.nextSetBit(0);bit>=0;bit=captured.nextSetBit(bit+1))if(p.id.equals(provinceAt(p.owner,ClaimManager.cellPosition(change.key(),bit))))p.lossBoundary.computeIfAbsent(change.key(),ignored->new BitSet()).set(bit);
                }
            }p.conqueror=next.getOwningStateId();changed();
        }
    }
    //observes the specified block position and updates the knowledge base with the relevant information
    public void observe(UUID viewer,BlockPos at,ClaimManager claims,long now){
        StateData nation=states.getState(viewer);if(nation==null||nation.isAI())return;
        long key=ClaimManager.regionKey(ChunkPos.containing(at));ListTag local=new ListTag();
        Set<UUID> owners=new LinkedHashSet<>();var region=claims.getStrategicRegions().get(key);if(region!=null)owners.addAll(region.owners());
        UUID concrete=claims.getStrategicOwner(ChunkPos.containing(at));if(concrete!=null)owners.add(concrete);
        for(UUID owner:owners)if(!owner.equals(viewer)&&states.getState(owner)!=null)for(var entry:masks(owner,claims).entrySet()){
            BitSet bits=entry.getValue().get(key);if(bits==null)continue;Division p=get(entry.getKey());CompoundTag fact=p.toNBT();fact.putLongArray("Land",bits.toLongArray());fact.putLong("Seen",now);
            SettlementData capital=states.getSettlement(p.capital);var known=states.getKnownSettlement(viewer,p.capital);
            if(capital!=null&&known!=null){fact.putLong("Position",known.position().asLong());fact.putBoolean("National",p.capital.equals(states.getState(owner).getCapitalSettlementId()));}else fact.remove("Capital");
            fact.remove("Conqueror");fact.remove("LossBoundary");fact.remove("ReleasedGarrisons");local.add(fact);
        }
        LinkedHashMap<Long,ListTag> learned=knowledge.computeIfAbsent(viewer,ignored->new LinkedHashMap<>());learned.put(key,local);while(learned.size()>8192)learned.remove(learned.keySet().iterator().next());states.setDirty();
    }

    //returns a snapshot of the province data for the specified player and request, including summaries of provinces and visible regions, along with any relevant messages
    public ProvincePayload snapshot(ServerPlayer player,ProvincePayload.Request request,String message){
        StateData nation=states.getStateByPlayer(player.getUUID());UUID viewer=nation==null?null:nation.getStateId();UUID subject=request.subject()==null?viewer:request.subject();
        ClaimManager claims=ClaimManager.get(player.level());reconcile(claims);boolean own=viewer!=null&&viewer.equals(subject);
        Map<UUID,Map<Long,BitSet>> source=new LinkedHashMap<>();List<ProvincePayload.Summary> summaries=new ArrayList<>();Draft draft=drafts.get(player.getUUID());boolean preview=own&&draft!=null;
        if(preview)draft.expires=player.level().getGameTime()+2400;
        if(own){
            masks(viewer,claims).forEach((id,parts)->{Map<Long,BitSet> copy=new LinkedHashMap<>();parts.forEach((key,bits)->copy.put(key,(BitSet)bits.clone()));source.put(id,copy);});
            if(preview){source.remove(draft.target);if(!draft.remove){for(var parts:source.values())draft.mask.forEach((key,bits)->{if(parts.containsKey(key))parts.get(key).andNot(bits);});source.put(draft.target,draft.mask);}
                UUID wrapper=defaultProvince(viewer);Map<Long,BitSet> fallback=source.computeIfAbsent(wrapper,ignored->new LinkedHashMap<>());
                claims.ownedRegionMasks(viewer).forEach((key,bits)->{BitSet remainder=(BitSet)bits.clone();for(var parts:source.values())if(parts.get(key)!=null)remainder.andNot(parts.get(key));if(!remainder.isEmpty())fallback.computeIfAbsent(key,ignored->new BitSet()).or(remainder);});}
            List<Division> list=new ArrayList<>(forState(viewer));if(preview){list.removeIf(p->p.id.equals(draft.target));if(!draft.remove)list.add(draft.division);}
            for(Division p:list){Map<Long,BitSet> area=source.getOrDefault(p.id,Map.of());List<SettlementData> sites=states.getAllSettlements().stream().filter(s->viewer.equals(s.getOwningStateId())&&contains(area,s.getCenterAltarPos())).toList();SettlementData capital=states.getSettlement(p.capital);
                summaries.add(new ProvincePayload.Summary(p.id,p.owner,p.name,p.kind.name(),p.capital,capital==null?null:capital.getCenterAltarPos(),p.capital!=null&&p.capital.equals(nation.getCapitalSettlementId()),sites.stream().mapToLong(SettlementData::getLocalPopulation).sum(),area.values().stream().mapToLong(BitSet::cardinality).sum(),sites.size(),player.level().getGameTime(),breaches.getOrDefault(p.id,Long.MIN_VALUE)>=player.level().getGameTime()-2400));}
        }else if(viewer!=null&&subject!=null){
            Map<UUID,CompoundTag> facts=new LinkedHashMap<>();knowledge.getOrDefault(viewer,new LinkedHashMap<>()).forEach((key,list)->{for(var value:list){CompoundTag fact=(CompoundTag)value;if(subject.toString().equals(fact.getString("Owner").orElse(""))){UUID id=UUID.fromString(fact.getString("Id").orElseThrow());source.computeIfAbsent(id,ignored->new LinkedHashMap<>()).put(key,BitSet.valueOf(fact.getLongArray("Land").orElseThrow()));CompoundTag before=facts.get(id);if(before==null||before.getLong("Seen").orElse(0L)<=fact.getLong("Seen").orElse(0L))facts.put(id,fact);}}});
            facts.values().stream().limit(MAX_PER_STATE).forEach(f->{Division p=Division.fromNBT(f);summaries.add(new ProvincePayload.Summary(p.id,p.owner,p.name,p.kind.name(),p.capital,f.contains("Position")?BlockPos.of(f.getLong("Position").orElseThrow()):null,f.getBoolean("National").orElse(false),-1,-1,-1,f.getLong("Seen").orElse(0L),false));});
        }
        List<ProvincePayload.Region> visible=new ArrayList<>();List<ProvincePayload.Edge> edges=new ArrayList<>();boolean truncated=false;
        int radius=request.span()/32+65,cx=Math.floorDiv(request.centerX(),16),cz=Math.floorDiv(request.centerZ(),16);
        List<Map.Entry<UUID,Map.Entry<Long,BitSet>>> candidates=new ArrayList<>();source.forEach((id,parts)->parts.entrySet().forEach(part->{ChunkPos p=ChunkPos.unpack(part.getKey());if(p.x()*64<=cx+radius&&(p.x()+1)*64>=cx-radius&&p.z()*64<=cz+radius&&(p.z()+1)*64>=cz-radius)candidates.add(Map.entry(id,part));}));
        candidates.sort(Comparator.comparingDouble(e->{ChunkPos p=ChunkPos.unpack(e.getValue().getKey());return Math.pow(p.x()*64+32.0-cx,2)+Math.pow(p.z()*64+32.0-cz,2);}));
        for(var candidate:candidates){if(visible.size()==ProvincePayload.MAX_REGIONS){truncated=true;break;}UUID id=candidate.getKey();long key=candidate.getValue().getKey();BitSet bits=candidate.getValue().getValue();visible.add(new ProvincePayload.Region(key,id,bits.toLongArray()));
            appendEdges(id,key,bits,source.get(id),own?null:knowledge.getOrDefault(viewer,new LinkedHashMap<>()).keySet(),edges);
            if(edges.size()>=ProvincePayload.MAX_EDGES)truncated=true;
        }
        if(preview&&draft.suggestions!=null)message="Preparing suggested boundaries…";
        else if(preview&&message.startsWith("Preparing"))message="Suggestion ready. Adjust the preview, apply or cancel.";
        return new ProvincePayload(request.epoch(),request.request(),viewer,subject,revision,own&&player.getUUID().equals(nation.getLeaderId()),preview,truncated,message,summaries.stream().limit(MAX_PER_STATE).toList(),visible,edges);
    }

    //returns true if the specified cell (chunk) is part of the province mask for the given source map, false otherwise
    private static boolean cell(Map<Long,BitSet> source,int x,int z){ChunkPos pos=new ChunkPos(x,z);BitSet mask=source.get(ClaimManager.regionKey(pos));return mask!=null&&mask.get(ClaimManager.cellBit(pos));}
    
    //appends the edges of the province boundaries to the provided list of edges, based on the specified province ID, region key, and bitset representing the land mask
    private static void appendEdges(UUID id,long key,BitSet bits,Map<Long,BitSet> complete,Set<Long> known,List<ProvincePayload.Edge> edges){
        ChunkPos origin=ChunkPos.unpack(key);int x=origin.x()*64,z=origin.z()*64;long[] rows=bits.toLongArray();
        long northKey=new ChunkPos(origin.x(),origin.z()-1).pack(),southKey=new ChunkPos(origin.x(),origin.z()+1).pack();
        long[] north=complete.getOrDefault(northKey,new BitSet()).toLongArray(),south=complete.getOrDefault(southKey,new BitSet()).toLongArray();
        for(int row=0;row<rows.length&&edges.size()<ProvincePayload.MAX_EDGES;row++){
            long cells=rows[row],above=row>0?rows[row-1]:known!=null&&!known.contains(northKey)?-1L:north.length==64?north[63]:0L;
            long below=row<63?(row+1<rows.length?rows[row+1]:0L):known!=null&&!known.contains(southKey)?-1L:south.length>0?south[0]:0L;
            horizontal(id,cells&~above,x,z+row,edges);horizontal(id,cells&~below,x,z+row+1,edges);
            long west=cells&~(cells<<1),east=cells&~(cells>>>1);
            long westKey=new ChunkPos(origin.x()-1,origin.z()).pack(),eastKey=new ChunkPos(origin.x()+1,origin.z()).pack();
            if(known!=null&&!known.contains(westKey)||cell(complete,x-1,z+row))west&=~1L;
            if(known!=null&&!known.contains(eastKey)||cell(complete,x+64,z+row))east&=Long.MAX_VALUE;
            while(west!=0&&edges.size()<ProvincePayload.MAX_EDGES){int col=Long.numberOfTrailingZeros(west);edges.add(new ProvincePayload.Edge(id,x+col,z+row,x+col,z+row+1));west&=west-1;}
            while(east!=0&&edges.size()<ProvincePayload.MAX_EDGES){int col=Long.numberOfTrailingZeros(east);edges.add(new ProvincePayload.Edge(id,x+col+1,z+row,x+col+1,z+row+1));east&=east-1;}
        }
    }
    //appends the horizontal edges of the province boundaries to the provided list of edges, based on the specified province ID, region key, and bitset representing the land mask
    private static void horizontal(UUID id,long bits,int x,int z,List<ProvincePayload.Edge> edges){while(bits!=0&&edges.size()<ProvincePayload.MAX_EDGES){int start=Long.numberOfTrailingZeros(bits),length=Long.numberOfTrailingZeros(~(bits>>>start));length=Math.min(length,64-start);edges.add(new ProvincePayload.Edge(id,x+start,z,x+start+length,z));long mask=length==64?-1L:((1L<<length)-1)<<start;bits&=~mask;}}
    public CompoundTag toNBT(){
        CompoundTag tag=new CompoundTag();tag.putInt("Version",1);tag.putLong("Revision",revision);ListTag records=new ListTag();divisions.values().forEach(p->records.add(p.toNBT()));tag.put("Divisions",records);
        CompoundTag wrappers=new CompoundTag();defaults.forEach((owner,id)->wrappers.putString(owner.toString(),id.toString()));tag.put("Defaults",wrappers);
        ListTag masks=new ListTag();membership.forEach((key,parts)->parts.forEach((id,bits)->{CompoundTag t=new CompoundTag();t.putLong("Key",key);t.putString("Province",id.toString());t.putLongArray("Land",bits.toLongArray());masks.add(t);}));tag.put("Membership",masks);
        ListTag learned=new ListTag();knowledge.forEach((viewer,regions)->regions.forEach((key,facts)->{CompoundTag t=new CompoundTag();t.putString("Viewer",viewer.toString());t.putLong("Key",key);t.put("Facts",facts.copy());learned.add(t);}));tag.put("Knowledge",learned);return tag;
    }

    //loads the province data from the provided CompoundTag, validating the version and populating the divisions, defaults, membership, and knowledge maps accordingly
    public void load(CompoundTag tag){
        if(tag.isEmpty())return;if(tag.getInt("Version").orElse(0)!=1)throw new IllegalArgumentException("Unsupported province save version");revision=Math.max(1,tag.getLong("Revision").orElse(1L));
        for(var value:tag.getListOrEmpty("Divisions")){Division p=Division.fromNBT((CompoundTag)value);if(divisions.putIfAbsent(p.id,p)!=null)throw new IllegalArgumentException("Duplicate province identity");}
        CompoundTag wrappers=tag.getCompound("Defaults").orElse(new CompoundTag());for(String key:wrappers.keySet())defaults.put(UUID.fromString(key),UUID.fromString(wrappers.getString(key).orElseThrow()));
        for(var value:tag.getListOrEmpty("Membership")){CompoundTag t=(CompoundTag)value;long[] bits=t.getLongArray("Land").orElseThrow();if(bits.length>64)throw new IllegalArgumentException("Oversized province mask");membership.computeIfAbsent(t.getLong("Key").orElseThrow(),ignored->new LinkedHashMap<>()).put(UUID.fromString(t.getString("Province").orElseThrow()),BitSet.valueOf(bits));}
        for(var value:tag.getListOrEmpty("Knowledge")){CompoundTag t=(CompoundTag)value;var learned=knowledge.computeIfAbsent(UUID.fromString(t.getString("Viewer").orElseThrow()),ignored->new LinkedHashMap<>());if(learned.size()<8192)learned.put(t.getLong("Key").orElseThrow(),t.getListOrEmpty("Facts").copy());}
    }
    public static ListTag saveMasks(Map<Long,BitSet> masks){ListTag result=new ListTag();masks.forEach((key,bits)->{CompoundTag t=new CompoundTag();t.putLong("Key",key);t.putLongArray("Land",bits.toLongArray());result.add(t);});return result;}
    public static Map<Long,BitSet> readMasks(ListTag list){Map<Long,BitSet> result=new LinkedHashMap<>();for(var value:list){CompoundTag t=(CompoundTag)value;long[] bits=t.getLongArray("Land").orElseThrow();if(bits.length>64)throw new IllegalArgumentException("Invalid province selection");result.put(t.getLong("Key").orElseThrow(),BitSet.valueOf(bits));}return result;}
    public void applyConquest(UUID id,UUID recipient,Map<Long,BitSet> selected){Division p=get(id);if(p==null)throw new IllegalStateException("Missing province");UUID donor=p.owner;p.owner=recipient;p.conqueror=null;p.lossBoundary.clear();p.releasedGarrisons.clear();if(id.equals(defaults.get(donor)))defaults.remove(donor);selected.forEach((key,bits)->assign(id,key,bits));changed();}
    public void surrender(UUID donor,UUID recipient,CompoundTag saved){
        for(var value:saved.getListOrEmpty("ProvinceAuthority")){CompoundTag entry=(CompoundTag)value;Division p=Division.fromNBT(entry.getCompound("Division").orElseThrow());if(p.owner.equals(donor))p.owner=recipient;p.conqueror=null;divisions.put(p.id,p);readMasks(entry.getListOrEmpty("Masks")).forEach((key,bits)->assign(p.id,key,bits));}
        defaults.remove(donor);changed();
    }
    /** Returns a list of province authority entries for the specified owners and claims. */
    public ListTag authority(Set<UUID> owners,ClaimManager claims){ListTag result=new ListTag();for(UUID owner:owners)for(Division p:forState(owner)){CompoundTag entry=new CompoundTag();entry.put("Division",p.toNBT());entry.put("Masks",saveMasks(provinceMask(p.id,claims)));result.add(entry);}return result;}
    /** Freeze one province's territorial assets; no treasury or remote troops change allegiance. */
    /*
     * This method is used to freeze the territorial assets of a province, preventing any changes to its treasury or remote troops.
        * It takes a CompoundTag plan, a UUID representing the province, and a ClaimManager as parameters.
        * The method retrieves the division information for the specified province and identifies the losing and recipient states involved in the transfer.
        * It prepares the transfer by saving the province ID, division information, and selected masks into the provided plan.
        * The method also identifies settlements owned by the losing state that are within the selected masks and adds their IDs to the plan.
        * It processes the military information, separating legions that belong to the losing state and preparing them for transfer to the recipient state.
        * The method handles infrastructure elements such as roads, camps, and bridges, ensuring that ownership is updated accordingly based on the selected masks.
        * Finally, the method updates the plan with the modified military and infrastructure information, completing the preparation for the transfer of territorial assets.
     */
    public void prepareTransfer(CompoundTag plan,UUID province,ClaimManager claims){
        Division p=get(province);UUID donor=p.owner,recipient=p.conqueror;
        Map<Long,BitSet> selected=p.lossBoundary.isEmpty()?provinceMask(province,claims):p.lossBoundary;plan.putString("ProvinceId",province.toString());plan.put("ProvinceDivision",p.toNBT());plan.put("ProvinceMasks",saveMasks(selected));
        Set<UUID> sites=new HashSet<>();
        for(var value:plan.getCompound("States").orElseThrow().getListOrEmpty("Settlements")){
            SettlementData site=SettlementData.fromNBT((CompoundTag)value);if(donor.equals(site.getOwningStateId())&&contains(selected,site.getCenterAltarPos()))sites.add(site.getSettlementId());
        }
        ListTag ids=new ListTag();sites.stream().sorted().forEach(id->ids.add(StringTag.valueOf(id.toString())));plan.put("ProvinceSites",ids);
        CompoundTag military=plan.getCompound("Armies").orElseThrow().copy();ListTag legions=new ListTag();Set<String> changedOrigins=new HashSet<>();
        for(var value:military.getListOrEmpty("Legions")){
            CompoundTag original=(CompoundTag)value;if(!donor.toString().equals(original.getString("OwningStateId").orElse(""))){legions.add(original.copy());continue;}
            CompoundTag kept=original.copy(),acquired=new Legion(stable("province-garrison:"+province+":"+plan.getLong("Sequence").orElseThrow()+":"+original.getString("LegionId").orElseThrow()),recipient,BlockPos.ZERO).toNBT();
            int moved=0,remaining=0;BlockPos position=null;
            for(String key:List.of("InfantryCohorts","CavalrySquadrons","Auxiliaries")){
                ListTag keep=new ListTag(),take=new ListTag();
                for(var item:original.getListOrEmpty(key)){
                    CompoundTag cohort=((CompoundTag)item).copy();Cohort parsed=Cohort.fromNBT(cohort);
                    boolean former=parsed.getStoredPosition().equals(p.releasedGarrisons.get(parsed.getCohortId()))&&parsed.getWaypoints().isEmpty();
                    if(parsed.isAlive()&&(parsed.isGarrisoned()&&sites.contains(parsed.getGarrisonedSettlementId())||former)){
                        if(former){cohort.putBoolean("IsGarrisoned",true);cohort.putString("GarrisonedSettlementId",p.capital.toString());}
                        cohort.putString("AssignedArmyId","");cohort.remove("Waypoints");take.add(cohort);moved++;position=parsed.getStoredPosition();
                    }else{keep.add(cohort);remaining++;}
                }
                kept.put(key,keep);acquired.put(key,take);
            }
            if(moved==0){legions.add(original.copy());continue;}
            changedOrigins.add(original.getString("LegionId").orElseThrow());
            if(position!=null){acquired.putLong("StoredPos",position.asLong());acquired.putDouble("PreciseX",position.getX()+.5);acquired.putDouble("PreciseZ",position.getZ()+.5);}
            if(remaining>0)legions.add(kept);else acquired.putString("LegionId",original.getString("LegionId").orElseThrow());
            legions.add(acquired);
        }
        military.put("Legions",legions);
        ListTag origins=new ListTag();for(var value:military.getListOrEmpty("DetailedOrigins"))if(!changedOrigins.contains(((CompoundTag)value).getString("Id").orElse("")))origins.add(value.copy());military.put("DetailedOrigins",origins);
        plan.put("ProvinceArmy",military);
        CompoundTag infrastructure=plan.getCompound("Logistics").orElseThrow().copy();
        // A road spanning the border is partitioned into stable contiguous segments; no road cells are lost.
        ListTag roads=new ListTag();for(var value:infrastructure.getListOrEmpty("Roads")){
            CompoundTag road=(CompoundTag)value;if(!donor.toString().equals(road.getString("Owner").orElse(""))){roads.add(road.copy());continue;}
            List<CompoundTag> run=new ArrayList<>();Boolean inside=null;int segment=0;
            for(var point:road.getListOrEmpty("Points")){CompoundTag pt=(CompoundTag)point;boolean take=contains(selected,BlockPos.of(pt.getLong("Position").orElseThrow()));
                if(inside!=null&&take!=inside){roads.add(roadRun(road,run,inside?recipient:donor,province,segment++));run=new ArrayList<>();}inside=take;run.add(pt.copy());}
            if(!run.isEmpty())roads.add(roadRun(road,run,Boolean.TRUE.equals(inside)?recipient:donor,province,segment));
        }infrastructure.put("Roads",roads);
        for(var value:infrastructure.getListOrEmpty("Camps")){CompoundTag camp=(CompoundTag)value;if(donor.toString().equals(camp.getString("Owner").orElse(""))&&contains(selected,BlockPos.of(camp.getLong("Position").orElseThrow())))camp.putString("Owner",recipient.toString());}
        for(var value:infrastructure.getListOrEmpty("Bridges")){CompoundTag bridge=(CompoundTag)value;if(donor.toString().equals(bridge.getString("Owner").orElse(""))&&contains(selected,BlockPos.of(bridge.getLong("Start").orElseThrow()))&&contains(selected,BlockPos.of(bridge.getLong("End").orElseThrow())))bridge.putString("Owner",recipient.toString());}
        plan.put("ProvinceLogistics",infrastructure);
    }

    //this method is responsible for creating a new road segment based on the provided source road, a list of points, the owner, province ID, and segment number. It copies the source road and updates its points and owner information.
    //If the number of points in the new segment differs from the original road's points, it generates a stable ID for the new road segment and adds control points to it. Finally, it returns the modified road segment.
    private static CompoundTag roadRun(CompoundTag source,List<CompoundTag> points,UUID owner,UUID province,int segment){
        CompoundTag road=source.copy();ListTag positions=new ListTag();points.forEach(positions::add);road.put("Points",positions);road.putString("Owner",owner.toString());
        // Keep an untouched road's identity; only genuine cross-boundary splits introduce segment IDs.
        if(points.size()!=source.getListOrEmpty("Points").size()){road.putString("Id",stable("province-road:"+province+":"+source.getString("Id").orElseThrow()+":"+segment).toString());ListTag controls=new ListTag();controls.add(points.getFirst().copy());if(points.size()>1)controls.add(points.getLast().copy());road.put("ControlPoints",controls);}return road;
    }
    /*
     * Applies a transfer of a province from one state to another.
     * @param plan The plan containing the transfer details.
     * @param states The state manager.
     * @param claims The claim manager.
     * @param armies The army manager.
     * @param logistics The logistics manager.
     * it validates the transfer plan, checks for conflicts, and applies the changes to the relevant managers.
     * It ensures that the province's identity, ownership, and military information are consistent with the transfer plan, and updates the state, claim, army, and logistics managers accordingly.
     */
    public static void applyTransfer(CompoundTag plan,StateManager states,ClaimManager claims,ArmyManager armies,com.devc.minecraftempires.army.LogisticsManager logistics){
        long seq=plan.getLong("Sequence").orElseThrow();UUID donor=UUID.fromString(plan.getString("Donor").orElseThrow()),recipient=UUID.fromString(plan.getString("Recipient").orElseThrow()),id=UUID.fromString(plan.getString("ProvinceId").orElseThrow());
        for(long receipt:new long[]{states.getInteractionSequence(),claims.getInteractionSequence(),armies.getInteractionSequence(),logistics.getInteractionSequence()})if(seq<1||receipt>seq||receipt<seq-1)throw new IllegalStateException("Conflicting provincial handover receipts");
        Map<Long,BitSet> selection=readMasks(plan.getListOrEmpty("ProvinceMasks"));Set<UUID> sites=new HashSet<>();for(var value:plan.getListOrEmpty("ProvinceSites"))sites.add(UUID.fromString(value.asString().orElseThrow()));
        Division saved=Division.fromNBT(plan.getCompound("ProvinceDivision").orElseThrow());if(!saved.id.equals(id)||!saved.owner.equals(donor)||!recipient.equals(saved.conqueror)||donor.equals(recipient))throw new IllegalArgumentException("Invalid province handover identity");
        if(states.getInteractionSequence()<seq){Division current=states.getProvinces().get(id);if(current==null||!current.owner.equals(donor)||states.getState(donor)==null||states.getState(recipient)==null)throw new IllegalStateException("Provincial identity conflict");}
        // Decode all affected domain objects before the first mutation; recovery then uses frozen projections.
        Map<UUID,Integer> before=cohortCounts(plan.getCompound("Armies").orElseThrow(),Set.of(donor,recipient)),after=cohortCounts(plan.getCompound("ProvinceArmy").orElseThrow(),Set.of(donor,recipient));
        if(!before.equals(after))throw new IllegalStateException("Provincial handover changes troop identity or strength");
        for(var value:plan.getCompound("ProvinceArmy").orElseThrow().getListOrEmpty("Legions"))Legion.fromNBT((CompoundTag)value);
        for(var value:plan.getCompound("States").orElseThrow().getListOrEmpty("Settlements")){SettlementData site=SettlementData.fromNBT((CompoundTag)value);if(sites.contains(site.getSettlementId())&&(!donor.equals(site.getOwningStateId())||!contains(selection,site.getCenterAltarPos())))throw new IllegalArgumentException("Foreign provincial settlement");}
        for(var value:plan.getCompound("Claims").orElseThrow().getListOrEmpty("StrategicRegions"))ClaimManager.StrategicRegion.fromNBT((CompoundTag)value);
        armies.applyProvinceSnapshot(seq,donor,recipient,plan.getCompound("ProvinceArmy").orElseThrow());
        claims.applyProvinceTransfer(seq,donor,recipient,plan.getCompound("Claims").orElseThrow(),selection);
        logistics.applyProvinceSnapshot(seq,donor,recipient,plan.getCompound("ProvinceLogistics").orElseThrow());
        states.applyProvinceTransfer(seq,plan.getLong("Time").orElseThrow(),donor,recipient,id,sites,selection,plan.getCompound("States").orElseThrow());
    }
    private static Map<UUID,Integer> cohortCounts(CompoundTag military,Set<UUID> owners){Map<UUID,Integer> counts=new HashMap<>();Set<UUID> legions=new HashSet<>();for(var value:military.getListOrEmpty("Legions")){CompoundTag legion=(CompoundTag)value;UUID id=UUID.fromString(legion.getString("LegionId").orElseThrow());if(!legions.add(id)||!owners.contains(UUID.fromString(legion.getString("OwningStateId").orElseThrow())))throw new IllegalArgumentException("Foreign or duplicate provincial legion");for(String key:List.of("InfantryCohorts","CavalrySquadrons","Auxiliaries"))for(var c:legion.getListOrEmpty(key)){Cohort cohort=Cohort.fromNBT((CompoundTag)c);if(counts.putIfAbsent(cohort.getCohortId(),cohort.getSoldierCount())!=null)throw new IllegalArgumentException("Duplicate provincial cohort");}}return counts;}
    private static UUID optional(CompoundTag t,String key){return t.getString(key).map(UUID::fromString).orElse(null);}
    private static UUID stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
}
