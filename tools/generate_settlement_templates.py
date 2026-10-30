"""Original Empires construction kit. Run with Python 3; no external assets/packages.

The checked-in NBT files are ordinary Minecraft StructureTemplates. Pools select
weighted variants; SettlementConstruction schedules their blocks incrementally.
"""
from pathlib import Path
import gzip
import json
import struct

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/data/minecraftempires"
VERSION = "construction/v1"


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def payload(kind, value):
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 8:
        return string(value)
    if kind == 9:
        child, values = value
        return bytes([child]) + struct.pack(">i", len(values)) + b"".join(payload(child, v) for v in values)
    if kind == 10:
        return b"".join(bytes([t]) + string(k) + payload(t, v) for k, (t, v) in value.items()) + b"\0"
    raise ValueError(kind)


class Build:
    def __init__(self, x, y, z):
        self.size = (x, y, z)
        self.blocks = {}

    def set(self, x, y, z, block, **properties):
        if not (0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]):
            raise ValueError((self.size, x, y, z))
        self.blocks[x, y, z] = (block if ":" in block else "minecraft:" + block, tuple(sorted(properties.items())))

    def box(self, x0, y0, z0, x1, y1, z1, block, **properties):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                for x in range(x0, x1 + 1):
                    self.set(x, y, z, block, **properties)

    def clear(self):
        self.box(0, 1, 0, self.size[0] - 1, self.size[1] - 1, self.size[2] - 1, "air")

    def write(self, name):
        palette = sorted(set(self.blocks.values()))
        ids = {state: i for i, state in enumerate(palette)}
        states = []
        for block, props in palette:
            state = {"Name": (8, block)}
            if props:
                state["Properties"] = (10, {key: (8, str(value)) for key, value in props})
            states.append(state)
        blocks = [{"pos": (9, (3, list(pos))), "state": (3, ids[state])} for pos, state in sorted(self.blocks.items(), key=lambda p: (p[0][1], p[0][2], p[0][0]))]
        root = {"DataVersion": (3, 4903), "author": (8, "Minecraft Empires original construction kit"),
                "size": (9, (3, list(self.size))), "palette": (9, (10, states)),
                "blocks": (9, (10, blocks)), "entities": (9, (10, []))}
        path = ROOT / "structure" / VERSION / (name + ".nbt")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(gzip.compress(b"\x0a\x00\x00" + payload(10, root), mtime=0))


def cottage(name, variant=False, fort=False):
    b = Build(9, 10, 9)
    b.clear()
    b.box(0, 0, 0, 8, 0, 8, "cobbled_deepslate" if fort else "stone_bricks")
    b.box(1, 1, 1, 7, 4, 7, "stone_bricks" if fort else "white_terracotta" if variant else "calcite")
    b.box(2, 1, 2, 6, 3, 6, "air")
    b.box(2, 0, 2, 6, 0, 6, "spruce_planks")
    for x in (1, 7):
        for z in (1, 7):
            b.box(x, 1, z, x, 4, z, "stripped_spruce_log", axis="y")
    for x in (1, 7):
        for z in (3, 5):
            b.set(x, 2, z, "glass")
    for x in (3, 5):
        b.set(x, 2, 1, "glass")
    b.box(4, 1, 7, 4, 2, 8, "air")
    b.set(4, 1, 7, "spruce_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.set(4, 2, 7, "spruce_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    b.set(4, 1, 6, "lantern")
    b.box(1, 4, 1, 7, 4, 7, "spruce_planks")
    roof = "deepslate_tile_stairs" if variant or fort else "spruce_stairs"
    for rise in range(4):
        for z in range(9):
            b.set(rise, 5 + rise, z, roof, facing="east", half="bottom", shape="straight", waterlogged="false")
            b.set(8 - rise, 5 + rise, z, roof, facing="west", half="bottom", shape="straight", waterlogged="false")
    b.box(4, 8, 0, 4, 8, 8, "deepslate_tiles" if variant or fort else "spruce_planks")
    for x in range(1, 8):
        for y in range(5, min(8, 5 + min(x, 8-x))):
            for z in (1, 7):
                b.set(x, y, z, "spruce_planks")
    b.set(4, 6, 7, "glass")
    b.set(2, 1, 3, "crafting_table")
    b.box(6, 5, 2, 6, 9, 2, "stone_bricks")
    b.set(6, 9, 2, "stone_brick_slab", type="bottom", waterlogged="false")
    if not fort:
        b.box(0, 1, 3, 0, 1, 5, "spruce_trapdoor", facing="west", half="bottom", open="true", powered="false", waterlogged="false")
        b.box(0, 2, 3, 0, 2, 5, "oak_leaves", persistent="true", distance="7", waterlogged="false")
    b.write(name)


def hall(name, military=False):
    b = Build(11, 12, 13)
    b.clear()
    b.box(0, 0, 0, 10, 0, 12, "stone_bricks")
    b.box(1, 1, 1, 9, 5, 11, "stone_bricks" if military else "smooth_sandstone")
    b.box(2, 1, 2, 8, 4, 10, "air")
    b.box(2, 0, 2, 8, 0, 10, "polished_andesite")
    for x in (1, 9):
        for z in (1, 5, 9, 11):
            b.box(x, 1, z, x, 5, z, "polished_andesite")
        for z in (3, 7):
            b.box(x, 2, z, x, 3, z, "glass")
    b.box(4, 1, 11, 6, 3, 12, "air")
    b.box(3, 5, 11, 7, 5, 12, "chiseled_stone_bricks")
    for rise in range(5):
        for z in range(13):
            for x, facing in ((rise, "east"), (10 - rise, "west")):
                b.set(x, 6 + rise, z, "polished_deepslate_stairs", facing=facing, half="bottom", shape="straight", waterlogged="false")
    b.box(5, 10, 0, 5, 10, 12, "polished_deepslate")
    for x in range(1, 10):
        for y in range(6, min(10, 6 + min(x, 10-x))):
            for z in (1, 11):
                b.set(x, y, z, "stone_bricks" if military else "smooth_sandstone")
    for z in (3, 8):
        b.set(5, 1, z, "lantern")
        for x in (3, 7):
            b.box(x, 1, z, x, 1, z + 1, "spruce_slab", type="bottom", waterlogged="false")
    b.write(name)


def workshop():
    b = Build(11, 9, 9)
    b.clear()
    b.box(0, 0, 0, 10, 0, 8, "cobblestone")
    b.box(1, 1, 1, 9, 3, 5, "bricks")
    b.box(2, 1, 2, 8, 2, 4, "air")
    b.box(4, 1, 5, 6, 2, 5, "air")
    b.box(0, 4, 0, 10, 4, 7, "dark_oak_slab", type="bottom", waterlogged="false")
    for x in (1, 9):
        b.box(x, 1, 7, x, 3, 7, "stripped_dark_oak_log", axis="y")
    b.box(8, 1, 2, 8, 8, 2, "stone_bricks")
    b.box(2, 1, 6, 3, 1, 6, "polished_andesite")
    b.set(5, 1, 3, "lantern")
    b.write("workshop")


def market():
    b = Build(9, 7, 9)
    b.clear()
    b.box(0, 0, 0, 8, 0, 8, "smooth_sandstone")
    for x in (1, 7):
        for z in (1, 7):
            b.box(x, 1, z, x, 3, z, "oak_fence")
    for x in range(9):
        b.box(x, 4, 0, x, 4, 8, "red_wool" if x % 2 else "white_wool")
    b.box(2, 1, 2, 2, 1, 6, "spruce_planks")
    b.box(6, 1, 2, 6, 1, 6, "spruce_planks")
    b.set(4, 3, 4, "lantern", hanging="true", waterlogged="false")
    b.write("market")


def plaza():
    b = Build(25, 5, 25)
    b.clear()
    for x in range(25):
        for z in range(25):
            b.set(x, 0, z, "polished_andesite" if x in (0, 24) or z in (0, 24) else "smooth_stone" if (x + z) % 4 else "stone_bricks")
    # The open square gives ten-wide formations room to turn around the altar.
    b.set(12, 1, 12, "minecraftempires:city_altar")
    b.write("plaza")


def wall(name, timber):
    b = Build(8, 9, 3)
    block = "stripped_dark_oak_log" if timber else "stone_bricks"
    b.box(0, 0, 0, 7, 5, 2, block)
    if timber:
        for x in range(0, 8, 2):
            b.box(x, 6, 0, x, 6, 2, "dark_oak_fence")
    else:
        b.box(0, 5, 0, 7, 5, 2, "polished_andesite")
        for x in range(0, 8, 2):
            b.box(x, 6, 0, x, 6, 0, "stone_bricks")
    b.write(name)


def tower(name, timber):
    b = Build(9, 15, 9)
    b.clear()
    material = "dark_oak_planks" if timber else "stone_bricks"
    b.box(0, 0, 0, 8, 10, 8, material)
    b.box(2, 1, 2, 6, 9, 6, "air")
    b.box(1, 10, 1, 7, 10, 7, "spruce_planks" if timber else "polished_andesite")
    for x in range(9):
        for z in range(9):
            if x in (0, 8) or z in (0, 8):
                b.set(x, 11, z, material)
                if (x + z) % 2 == 0:
                    b.set(x, 12, z, material)
    for x in (0, 8):
        for z in (0, 8):
            b.box(x, 0, z, x, 10, z, "stripped_spruce_log" if timber else "polished_andesite")
    b.box(4, 1, 7, 4, 2, 8, "air")
    b.box(1, 1, 4, 1, 10, 4, "ladder", facing="east", waterlogged="false")
    b.set(4, 11, 4, "lantern")
    b.write(name)


def gate(name, timber):
    # Keep the eleven-wide road open to the sky: top-surface army surveys must
    # see the street, not a roof above it. Flanking bastions carry the silhouette.
    b = Build(17, 13, 9)
    b.clear()
    material = "dark_oak_planks" if timber else "stone_bricks"
    for x0, x1 in ((0, 2), (14, 16)):
        b.box(x0, 0, 0, x1, 9, 8, material)
        b.box(x0, 5, 0, x1, 5, 8, "stripped_spruce_log" if timber else "polished_andesite")
        for x in range(x0, x1 + 1):
            for z in (0, 8):
                b.set(x, 10, z, material)
                if x % 2 == 0:
                    b.set(x, 11, z, material)
        b.set((x0 + x1) // 2, 10, 4, "lantern")
        b.box((x0+x1)//2, 1, 8, (x0+x1)//2, 10, 8, "ladder", facing="south", waterlogged="false")
    b.box(3, 0, 0, 13, 0, 8, "stone_bricks")
    b.write(name)


def pool(name, choices):
    out = ROOT / "worldgen/template_pool" / VERSION / (name + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    content = {"fallback": "minecraft:empty", "elements": [
        {"weight": weight, "element": {"element_type": "minecraft:single_pool_element",
         "location": "minecraftempires:" + VERSION + "/" + module,
         "processors": "minecraftempires:" + VERSION + "/masonry", "projection": "rigid"}}
        for module, weight in choices]}
    out.write_text(json.dumps(content, indent=2) + "\n", encoding="utf-8")


def main():
    plaza()
    cottage("cottage_a")
    cottage("cottage_b", True)
    cottage("keep", fort=True)
    hall("civic_hall")
    hall("barracks", True)
    workshop()
    market()
    for timber, prefix in ((False, "stone"), (True, "timber")):
        wall(prefix + "_wall", timber)
        tower(prefix + "_tower", timber)
        gate(prefix + "_gate", timber)
    for name in ("plaza", "keep", "civic_hall", "barracks", "workshop", "market", "stone_wall", "stone_tower", "stone_gate", "timber_wall", "timber_tower", "timber_gate"):
        pool(name, [(name, 1)])
    pool("homes", [("cottage_a", 3), ("cottage_b", 2)])
    path = ROOT / "worldgen/processor_list" / VERSION / "masonry.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"processors": [{"processor_type": "minecraft:rule", "rules": [{
        "input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:stone_bricks", "probability": 0.08},
        "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:cracked_stone_bricks"}}]}]}, indent=2) + "\n", encoding="utf-8")
    print("Generated 14 original StructureTemplates, 13 pools and one masonry processor list.")


if __name__ == "__main__":
    main()
