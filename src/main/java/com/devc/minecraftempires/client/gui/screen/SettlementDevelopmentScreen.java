package com.devc.minecraftempires.client.gui.screen;

import com.devc.minecraftempires.network.packet.SettlementDevelopmentPayload;
import com.devc.minecraftempires.network.packet.StateActionPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import java.util.List;
import java.util.UUID;

/** The server owns selections, permissions, payment and construction; this screen only drafts requests. */
/*
Settlement development screen, used for registering existing builds, saving blueprints, claiming settlements, and constructing settlements. (same screen shown when opening altars in villages)
The server owns selections, permissions, payment and construction; this screen only drafts requests.
*/
public final class SettlementDevelopmentScreen extends Screen {
    private SettlementDevelopmentPayload view;
    private EditBox first,second,name;
    private String local="";
    private int chosen,rotation,left,column,right,previewTop,scroll,maxScroll;
    public SettlementDevelopmentScreen(SettlementDevelopmentPayload view){super(Component.literal("Settlement building"));this.view=view;}
    public boolean matches(SettlementDevelopmentPayload p){return view.session().equals(p.session())&&view.owner().equals(p.owner())&&view.altar().equals(p.altar());}
    public void accept(SettlementDevelopmentPayload p){if(matches(p)){view=p;chosen=Math.min(chosen,Math.max(0,view.blueprints().size()-1));rebuildWidgets();}}
    private static String coords(BlockPos p){return p.getX()+", "+p.getY()+", "+p.getZ();}
    private static BlockPos parse(String text){String[] v=text.trim().split("[ ,]+");if(v.length!=3)throw new IllegalArgumentException();return new BlockPos(Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2]));}
    private void send(String action,UUID id,String text,List<BlockPos> corners){local="";ClientPacketDistributor.sendToServer(new StateActionPayload(action,id,view.altar(),text,corners));}
    private void selection(String action){try{send(action,view.session(),name.getValue(),List.of(parse(first.getValue()),parse(second.getValue())));}catch(IllegalArgumentException bad){local="Enter each corner as X, Y, Z.";}}
    private boolean shown(int y){return y>=28&&y+20<=height-34;}
    private EditBox field(int x,int y,int w,String label,String value){var b=new EditBox(font,x,y-scroll,w,20,Component.literal(label));b.setMaxLength(48);b.setValue(value);b.visible=shown(y-scroll);addRenderableWidget(b);return b;}
    private void button(String text,int x,int y,int w,Runnable action){var b=Button.builder(Component.literal(text),ignored->action.run()).bounds(x,y-scroll,w,20).build();b.visible=shown(y-scroll);addRenderableWidget(b);}
    @Override protected void init(){
        left=12;column=Math.max(100,(width-36)/2);right=left+column+12;previewTop=151;
        int lines=font.split(Component.literal(local.isEmpty()?view.message():local),Math.max(120,width-24)).size();maxScroll=Math.max(0,268+lines*(font.lineHeight+2)+10-(height-34));scroll=Math.min(scroll,maxScroll);
        String n=name==null?"My settlement":name.getValue(),a=first==null?coords(view.altar().offset(-4,-1,-4)):first.getValue(),b=second==null?coords(view.altar().offset(4,5,4)):second.getValue();
        name=field(left,44,column,"Build name",n);first=field(left,82,column,"First corner X Y Z",a);second=field(left,120,column,"Second corner X Y Z",b);
        button("Register existing build",left,148,column,()->selection("ALTAR_REGISTER"));
        button("Save blueprint",left,172,column,()->selection("ALTAR_SAVE"));
        button("Claim settlement",left,196,column,()->send("ALTAR_CLAIM",view.session(),name.getValue(),List.of()));
        String selected=view.blueprints().isEmpty()?"No saved blueprints":view.blueprints().get(chosen).name();
        button(selected,right,44,column,()->{if(!view.blueprints().isEmpty()){chosen=(chosen+1)%view.blueprints().size();rebuildWidgets();}});
        button("Rotation: "+rotation*90+" degrees",right,70,column,()->{rotation=(rotation+1)%4;rebuildWidgets();});
        button("Preview placement",right,96,column,()->{if(!view.blueprints().isEmpty())send("ALTAR_PREVIEW",view.blueprints().get(chosen).id(),Integer.toString(rotation),List.of());});
        button("Construct: "+view.cost(),right,122,column,()->{if(!view.preview().equals(SettlementDevelopmentPayload.NONE))send("ALTAR_BUILD",view.preview(),"",List.of());});
        button("Cancel preview",right,228,column,()->send("ALTAR_CANCEL",view.session(),"",List.of()));
        button("Delete blueprint",left,228,column,()->{if(!view.blueprints().isEmpty())send("ALTAR_DELETE",view.blueprints().get(chosen).id(),"",List.of());});
        addRenderableWidget(Button.builder(Component.literal("Close"),ignored->onClose()).bounds(left,height-28,column,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float pt){
        g.fill(0,0,width,height,0xF0101820);g.centeredText(font,title,width/2,12,0xFFFFD45A);
        g.enableScissor(0,28,width,Math.max(29,height-34));
        g.text(font,Component.literal("Build name / new state name"),left,32-scroll,0xFFE0E6EB);g.text(font,Component.literal("First block corner: X, Y, Z"),left,70-scroll,0xFFE0E6EB);g.text(font,Component.literal("Second block corner: X, Y, Z"),left,108-scroll,0xFFE0E6EB);
        int available=60;g.fill(right,previewTop-scroll,right+column,previewTop-scroll+available,0xFF152832);
        if(!view.cells().isEmpty()){
            int minX=view.cells().stream().mapToInt(SettlementDevelopmentPayload.Cell::x).min().orElse(0),maxX=view.cells().stream().mapToInt(SettlementDevelopmentPayload.Cell::x).max().orElse(0);
            int minZ=view.cells().stream().mapToInt(SettlementDevelopmentPayload.Cell::z).min().orElse(0),maxZ=view.cells().stream().mapToInt(SettlementDevelopmentPayload.Cell::z).max().orElse(0);
            double scale=Math.min((column-8.0)/(maxX-minX+1),(available-8.0)/(maxZ-minZ+1));
            int startX=right+(int)((column-(maxX-minX+1)*scale)/2),startZ=previewTop-scroll+(int)((available-(maxZ-minZ+1)*scale)/2);
            for(var cell:view.cells()){int x=startX+(int)((cell.x()-minX)*scale),z=startZ+(int)((cell.z()-minZ)*scale);int h=Math.floorMod(cell.y(),12)*5;g.fill(x,z,x+Math.max(1,(int)Math.ceil(scale)),z+Math.max(1,(int)Math.ceil(scale)),0xFF669900|((80+h)<<8));}
            g.text(font,Component.literal("Top view | North up"),right+4,previewTop+available+3-scroll,0xFFBAC5CE);
        }
        int y=268-scroll;String message=local.isEmpty()?view.message():local;
        for(var line:font.split(Component.literal(message),Math.max(120,width-24))){g.text(font,line,left,y,0xFFE0C985);y+=font.lineHeight+2;}
        g.disableScissor();if(maxScroll>0)g.text(font,Component.literal("Scroll for more"),right,height-22,0xFFBAC5CE);
        super.extractRenderState(g,mx,my,pt);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){scroll=Math.max(0,Math.min(maxScroll,scroll-(int)(vertical*24)));rebuildWidgets();return true;}
    @Override public void onClose(){send("ALTAR_CLOSE",view.session(),"",List.of());super.onClose();}
}
