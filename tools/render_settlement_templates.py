"""Isometric review images read the shipped NBT blocks; no invented geometry/assets.

Pillow is only needed for this optional authoring preview, never by the mod/build.
Also accepts a native fixture's exported layout as a normal structure NBT file.
"""
from pathlib import Path
from io import BytesIO
import gzip
import math
import struct
import argparse
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
COLORS = {"stone_bricks": "#99998d", "cracked_stone_bricks": "#84877d", "cobblestone": "#85877d", "polished_andesite": "#b0b0a4",
          "smooth_stone": "#bbb7a5", "smooth_sandstone": "#e5d7ad", "calcite": "#e4dfcc", "white_terracotta": "#cbb9a4",
          "bricks": "#ad7860", "glass": "#8db2b6", "spruce_planks": "#897050", "spruce_slab": "#897050", "spruce_stairs": "#6f5945",
          "dark_oak_planks": "#554535", "dark_oak_slab": "#554535", "stripped_spruce_log": "#746044", "stripped_dark_oak_log": "#594839",
          "deepslate_tiles": "#48545b", "deepslate_tile_stairs": "#48545b", "polished_deepslate_stairs": "#4e595d", "polished_deepslate": "#4e595d",
          "cobbled_deepslate": "#616967", "oak_fence": "#aa8d5e", "dark_oak_fence": "#67523b", "ladder": "#c5a573", "lantern": "#eab95b",
          "oak_leaves": "#62834f", "spruce_trapdoor": "#7e6448", "red_wool": "#a94d45", "white_wool": "#ede7d5", "gravel": "#a29b85",
          "stone_brick_slab": "#99998d", "chiseled_stone_bricks": "#99998d", "city_altar": "#80cbb0", "stone": "#8e938c"}


def read_nbt(path):
    stream = BytesIO(gzip.decompress(path.read_bytes()))
    def take(n): return stream.read(n)
    def number(fmt): return struct.unpack(fmt, take(struct.calcsize(fmt)))[0]
    def string(): return take(number(">H")).decode("utf-8")
    def value(t):
        if t == 1: return number(">b")
        if t == 3: return number(">i")
        if t == 4: return number(">q")
        if t == 6: return number(">d")
        if t == 8: return string()
        if t == 9:
            child, count = number(">B"), number(">i")
            return [value(child) for _ in range(count)]
        if t == 10:
            result = {}
            while True:
                child = number(">B")
                if not child: return result
                name = string()
                result[name] = value(child)
        raise ValueError("Unsupported preview NBT tag " + str(t))
    assert number(">B") == 10
    string()
    return value(10)


def shade(color, factor):
    channels = tuple(int(color[i:i+2], 16) for i in (1, 3, 5))
    return tuple(min(255, int(c * factor)) for c in channels)


def boxes(state):
    name = state["Name"].split(":")[-1]
    props = state.get("Properties", {})
    if name.endswith("stairs"):
        facing = props.get("facing", "north")
        yield (0, 0, 0, 1, .5, 1)
        yield {"north": (0,.5,0,1,1,.5), "south": (0,.5,.5,1,1,1), "east": (.5,.5,0,1,1,1), "west": (0,.5,0,.5,1,1)}[facing]
    elif name.endswith("slab"):
        yield (0, 0, 0, 1, .5, 1)
    elif name.endswith("fence"):
        yield (.375, 0, .375, .625, 1.25, .625)
    elif name == "lantern":
        yield (.25, .05, .25, .75, .75, .75)
    elif name == "ladder":
        yield (.85, 0, 0, 1, 1, 1)
    elif name.endswith("trapdoor"):
        yield (0, 0, 0, .18, 1, 1)
    else:
        yield (0, 0, 0, 1, 1, 1)


def render(path, width=680, height=600):
    data = read_nbt(path)
    blocks = [(tuple(b["pos"]), data["palette"][b["state"]]) for b in data["blocks"] if data["palette"][b["state"]]["Name"] not in ("minecraft:air", "minecraft:structure_void")]
    occupied = {p: state for p, state in blocks}
    def project(x, y, z): return (x-z, (x+z)*.47-y*1.08)
    projected = [project(x+dx, y+dy, z+dz) for (x,y,z), _ in blocks for dx,dy,dz in ((0,0,0),(1,0,1),(0,1,0),(1,1,1))]
    minx, maxx = min(p[0] for p in projected), max(p[0] for p in projected)
    miny, maxy = min(p[1] for p in projected), max(p[1] for p in projected)
    scale = min((width-70)/(maxx-minx), (height-65)/(maxy-miny))
    image = Image.new("RGBA", (width, height), (0,0,0,0))
    draw = ImageDraw.Draw(image)
    def p(x,y,z):
        a,b = project(x,y,z)
        return ((a-(minx+maxx)/2)*scale+width/2, (b-miny)*scale+25)
    for (x,y,z), state in sorted(blocks, key=lambda item: (sum(item[0]), item[0][1])):
        name=state["Name"].split(":")[-1]
        color=COLORS.get(name, "#959786")
        for x0,y0,z0,x1,y1,z1 in boxes(state):
            x0,x1,y0,y1,z0,z1=x+x0,x+x1,y+y0,y+y1,z+z0,z+z1
            cube = (x1-x0 == 1 and y1-y0 == 1 and z1-z0 == 1)
            def full_neighbor(pos):
                n=occupied.get(pos)
                return n is not None and not any(key in n["Name"] for key in ("slab","stairs","fence","lantern","ladder","trapdoor"))
            if not cube or not full_neighbor((x+1,y,z)):
                draw.polygon([p(x1,y0,z0),p(x1,y0,z1),p(x1,y1,z1),p(x1,y1,z0)], fill=shade(color,.72))
            if not cube or not full_neighbor((x,y,z+1)):
                draw.polygon([p(x0,y0,z1),p(x1,y0,z1),p(x1,y1,z1),p(x0,y1,z1)], fill=shade(color,.88))
            if not cube or not full_neighbor((x,y+1,z)):
                draw.polygon([p(x0,y1,z0),p(x1,y1,z0),p(x1,y1,z1),p(x0,y1,z1)], fill=shade(color,1.12))
    return image


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT/"build/construction-previews")
    parser.add_argument("--layout", type=Path)
    args=parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    font_path=Path("C:/Windows/Fonts/segoeui.ttf")
    def font(size): return ImageFont.truetype(str(font_path),size) if font_path.exists() else ImageFont.load_default()
    if args.layout:
        preview=Image.new("RGB",(1800,1500),"#eee9dc")
        preview.paste(render(args.layout,1800,1430),(0,70),render(args.layout,1800,1430))
        ImageDraw.Draw(preview).text((42,22),"Minecraft Empires | Built settlement geometry",font=font(29),fill="#283933")
        preview.save(args.output/(args.layout.stem+".png"))
        return
    modules=[("cottage_a","Timber cottage"),("cottage_b","Slate-roof cottage"),("civic_hall","Civic hall"),("market","Market pavilion"),
             ("workshop","Workshop"),("barracks","Barracks"),("stone_gate","Open gate bastions"),("stone_tower","Stone watchtower")]
    sheet=Image.new("RGB",(1600,1850),"#eee9dc")
    draw=ImageDraw.Draw(sheet)
    draw.text((42,28),"Minecraft Empires | Original settlement kit",font=font(38),fill="#283933")
    draw.text((44,82),"Actual template geometry · flat material colors · in-game textures and lighting will differ",font=font(21),fill="#657168")
    for index,(name,label) in enumerate(modules):
        x,y=30+(index%2)*785,130+(index//2)*420
        draw.rounded_rectangle((x,y,x+755,y+395),radius=16,fill="#faf7ee")
        tile=render(ROOT/"src/main/resources/data/minecraftempires/structure/construction/v1"/(name+".nbt"),735,345)
        sheet.paste(tile,(x+10,y+43),tile)
        draw.text((x+24,y+13),label,font=font(25),fill="#33473b")
    out=args.output/"original-settlement-kit.png"
    sheet.save(out)
    print(out)


if __name__=="__main__":main()
