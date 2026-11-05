import json
import os
import math
import random
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "src/main/resources/assets/hajikun_smart")
DATA = os.path.join(ROOT, "src/main/resources/data")
MOD = "hajikun_smart"

for sub in ("blockstates", "models/block", "items", "lang", "textures/block"):
    os.makedirs(os.path.join(ASSETS, sub), exist_ok=True)
os.makedirs(os.path.join(DATA, "hajikun_smart/recipe"), exist_ok=True)
os.makedirs(os.path.join(DATA, "minecraft/tags/block/mineable"), exist_ok=True)

random.seed(20261031)


def clamp(value):
    return max(0, min(255, int(value)))


def make_texture(name, painter):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    pixels = img.load()
    painter(pixels)
    img.save(os.path.join(ASSETS, "textures/block", name + ".png"))


def rp(pixels, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        pixels[x, y] = (clamp(color[0]), clamp(color[1]), clamp(color[2]), 255)


def vgrad(pixels, top, bottom, noise=3):
    for y in range(16):
        t = y / 15.0
        for x in range(16):
            n = random.randint(-noise, noise)
            rp(pixels, x, y, (top[0] + (bottom[0] - top[0]) * t + n,
                              top[1] + (bottom[1] - top[1]) * t + n,
                              top[2] + (bottom[2] - top[2]) * t + n))


def bevel(pixels, light=(70, 80, 94), dark=(6, 8, 11)):
    for i in range(16):
        rp(pixels, i, 0, light)
        rp(pixels, 0, i, light)
        rp(pixels, i, 15, dark)
        rp(pixels, 15, i, dark)


def chassis(pixels):
    vgrad(pixels, (38, 44, 54), (21, 26, 33))
    for y in range(2, 14):
        for x in range(2, 14):
            if (x * 7 + y * 3) % 11 == 0:
                rp(pixels, x, y, (30, 36, 45))
    bevel(pixels)
    rp(pixels, 2, 13, (96, 202, 232))
    rp(pixels, 3, 13, (60, 130, 160))


def chassis_dark(pixels):
    vgrad(pixels, (24, 29, 37), (12, 15, 20))
    bevel(pixels, (48, 56, 68), (4, 5, 8))
    for y in (4, 7, 10, 13):
        for x in range(3, 13):
            rp(pixels, x, y, (8, 10, 14))
            rp(pixels, x, y - 1, (34, 40, 49))


def bezel(pixels):
    vgrad(pixels, (18, 21, 27), (9, 11, 15), noise=2)
    for i in range(16):
        rp(pixels, i, 0, (74, 84, 98))
        rp(pixels, i, 1, (44, 52, 64))
        rp(pixels, i, 15, (5, 6, 9))
        rp(pixels, 0, i, (60, 70, 84))
        rp(pixels, 15, i, (6, 8, 10))
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        rp(pixels, x, y, (96, 104, 118))


def screen_off(pixels):
    vgrad(pixels, (10, 14, 19), (3, 5, 8), noise=1)
    for i in range(16):
        rp(pixels, i, 0, (46, 54, 66))
        rp(pixels, i, 15, (24, 28, 34))
        rp(pixels, 0, i, (40, 48, 58))
        rp(pixels, 15, i, (20, 24, 30))
    for d in range(4, 10):
        rp(pixels, d + 2, d, (20, 28, 37))
        rp(pixels, d + 3, d, (16, 22, 30))
    for x in range(3, 6):
        rp(pixels, x, 11, (14, 20, 27))


def screen_on(pixels):
    vgrad(pixels, (16, 60, 92), (52, 28, 78), noise=2)
    for x in range(2, 14):
        rp(pixels, x, 8, (26, 108, 148))
        rp(pixels, x, 9, (22, 92, 130))
    # 壁纸里的光斑
    for (x, y, c) in ((11, 5, (140, 226, 236)), (12, 5, (170, 240, 246)),
                      (11, 6, (110, 200, 216)), (12, 6, (140, 220, 232))):
        rp(pixels, x, y, c)
    # 桌面图标
    for x, y, c in ((2, 3, (232, 190, 84)), (2, 6, (70, 120, 210)),
                    (2, 9, (216, 222, 233)), (2, 12, (110, 132, 160))):
        rp(pixels, x, y, c)
        rp(pixels, x + 1, y, c)
        rp(pixels, x, y + 1, tuple(int(v * 0.75) for v in c))
        rp(pixels, x + 1, y + 1, tuple(int(v * 0.75) for v in c))
    # 底部任务栏
    for x in range(16):
        rp(pixels, x, 14, (10, 16, 24))
        rp(pixels, x, 15, (6, 10, 16))
    rp(pixels, 1, 14, (96, 202, 232))
    rp(pixels, 2, 14, (40, 120, 150))


def screen_cast(pixels):
    vgrad(pixels, (44, 20, 68), (14, 42, 80), noise=2)
    # 播放三角
    for i in range(5):
        for x in range(7, 7 + i + 1):
            rp(pixels, x, 6 + i, (236, 244, 252))
            rp(pixels, x, 10 - i, (236, 244, 252))
    for y in range(6, 11):
        rp(pixels, 11, y, (236, 244, 252))
    # 扫描线
    for y in (2, 5, 8, 11, 14):
        for x in range(16):
            r, g, b, _ = pixels[x, y]
            rp(pixels, x, y, (r * 0.8 + 18, g * 0.8 + 10, b * 0.8 + 28))
    # 投屏弧线
    for (x, y) in ((2, 2), (3, 2), (2, 3), (13, 13), (12, 13), (13, 12)):
        rp(pixels, x, y, (120, 210, 240))


def keyboard(pixels):
    vgrad(pixels, (20, 24, 31), (11, 14, 19), noise=2)
    for row in range(4):
        for col in range(6):
            x = 1 + col * 2 + (1 if row % 2 else 0)
            y = 2 + row * 3
            rp(pixels, x, y, (52, 60, 72))
            rp(pixels, x, y + 1, (36, 43, 53))
    for x in range(3, 13):
        rp(pixels, x, 14, (36, 180, 208) if x < 8 else (196, 64, 156))
    bevel(pixels, (44, 52, 64), (5, 6, 9))


def redstone(pixels):
    vgrad(pixels, (32, 14, 14), (16, 7, 7), noise=1)
    for x in range(2, 14):
        rp(pixels, x, 7, (220, 46, 38))
        rp(pixels, x, 8, (150, 24, 24))
    for (x, y) in ((4, 7), (8, 7), (11, 7), (6, 8), (10, 8)):
        rp(pixels, x, y, (255, 132, 96))
    bevel(pixels, (66, 28, 26), (8, 4, 4))


def tv_frame(pixels):
    vgrad(pixels, (13, 15, 19), (7, 8, 11), noise=1)
    for i in range(16):
        rp(pixels, i, 0, (46, 52, 62))
        rp(pixels, i, 15, (4, 5, 7))
        rp(pixels, 0, i, (32, 38, 46))
        rp(pixels, 15, i, (5, 6, 9))
    for d in range(1, 8):
        rp(pixels, 2 + d, 13 - d, (22, 26, 32))
    rp(pixels, 2, 13, (96, 202, 232))
    rp(pixels, 3, 13, (40, 110, 140))


def tv_off(pixels):
    # 纯色，避免多方块拼接时每格图案重复形成网格
    for y in range(16):
        for x in range(16):
            rp(pixels, x, y, (6, 8, 11))


def tv_on(pixels):
    for y in range(16):
        for x in range(16):
            rp(pixels, x, y, (9, 23, 52))


def tv_cast(pixels):
    for y in range(16):
        for x in range(16):
            rp(pixels, x, y, (21, 16, 50))


make_texture("chassis", chassis)
make_texture("chassis_dark", chassis_dark)
make_texture("bezel", bezel)
make_texture("screen_off", screen_off)
make_texture("screen_on", screen_on)
make_texture("screen_cast", screen_cast)
make_texture("keyboard", keyboard)
make_texture("redstone", redstone)
make_texture("tv_frame", tv_frame)
make_texture("tv_off", tv_off)
make_texture("tv_on", tv_on)
make_texture("tv_cast", tv_cast)


def element(frm, to, faces=None, rotation=None):
    data = {"from": frm, "to": to}
    if faces is None:
        data["faces"] = {k: {"texture": "#chassis"} for k in
                         ("north", "south", "east", "west", "up", "down")}
    else:
        data["faces"] = faces
    if rotation:
        data["rotation"] = rotation
    return data


def all_faces(texture):
    return {k: {"texture": texture} for k in ("north", "south", "east", "west", "up", "down")}


def screen_faces(screen, body):
    faces = all_faces(body)
    faces["north"] = {"texture": screen, "uv": [0, 0, 16, 16]}
    return faces


DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.62, 0.62, 0.62]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 1.5, 0], "scale": [0.4, 0.4, 0.4]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_lefthand": {"rotation": [0, 45, 0], "translation": [0, 1.5, 0], "scale": [0.4, 0.4, 0.4]},
}


def desktop_elements(screen_texture):
    return [
        # 键盘
        element([1.5, 0, 0.8], [14.5, 0.7, 7.0], {
            "up": {"texture": "#keyboard"},
            "down": {"texture": "#chassis_dark"},
            "north": {"texture": "#chassis"},
            "south": {"texture": "#chassis"},
            "east": {"texture": "#chassis_dark"},
            "west": {"texture": "#chassis_dark"},
        }),
        # 鼠标
        element([14.9, 0, 5.9], [15.8, 0.45, 7.1], {
            "up": {"texture": "#bezel"},
            **{k: {"texture": "#chassis_dark"} for k in ("north", "south", "east", "west", "down")},
        }),
        # 底座与支架
        element([6.4, 0.7, 8.9], [9.6, 1.25, 11.8], all_faces("#chassis_dark")),
        element([7.1, 1.25, 9.6], [8.9, 4.15, 10.6], all_faces("#chassis")),
        # 屏幕
        element([1.8, 4.0, 8.6], [14.2, 13.8, 10.0], screen_faces(screen_texture, "#chassis_dark")),
        # 边框
        element([1.5, 13.3, 8.5], [14.5, 14.1, 10.1], all_faces("#bezel")),
        element([1.5, 3.7, 8.5], [14.5, 4.5, 10.1], all_faces("#bezel")),
        element([1.5, 4.5, 8.5], [2.3, 13.3, 10.1], all_faces("#bezel")),
        element([13.7, 4.5, 8.5], [14.5, 13.3, 10.1], all_faces("#bezel")),
        # 物理电源键
        element([12.7, 4.6, 8.4], [13.6, 5.4, 8.62], {
            "north": {"texture": "#redstone"},
            **{k: {"texture": "#bezel"} for k in ("south", "east", "west", "up", "down")},
        }),
        # 主机箱
        element([13.6, 0, 10.3], [15.6, 8.6, 14.3], {
            "north": {"texture": "#chassis"},
            **{k: {"texture": "#chassis_dark"} for k in ("south", "east", "west", "up", "down")},
        }),
        element([14.6, 6.0, 10.24], [15.2, 6.6, 10.3], {
            "north": {"texture": "#redstone"},
            **{k: {"texture": "#bezel"} for k in ("south", "east", "west", "up", "down")},
        }),
    ]


def laptop_elements(screen_texture):
    return [
        # 机身
        element([1.0, 0, 1.0], [15.0, 0.8, 12.0], {
            "up": {"texture": "#chassis"},
            **{k: {"texture": "#chassis_dark"} for k in ("north", "south", "east", "west", "down")},
        }),
        # 键盘区
        element([2.2, 0.8, 2.2], [13.8, 0.9, 9.4], {
            "up": {"texture": "#keyboard"},
            **{k: {"texture": "#chassis_dark"} for k in ("north", "south", "east", "west", "down")},
        }),
        # 触控板
        element([6.4, 0.8, 9.8], [9.6, 0.92, 11.4], {
            "up": {"texture": "#bezel"},
            **{k: {"texture": "#chassis_dark"} for k in ("north", "south", "east", "west", "down")},
        }),
        # 掀开的屏幕（绕后沿后仰 15°）
        element([1.6, 0.8, 10.6], [14.4, 9.4, 11.2], screen_faces(screen_texture, "#chassis"),
                rotation={"origin": [8, 0.8, 11.0], "axis": "x", "angle": 15, "rescale": False}),
        # 屏幕边框
        element([1.5, 9.1, 10.55], [14.5, 9.6, 11.25], all_faces("#bezel"),
                rotation={"origin": [8, 0.8, 11.0], "axis": "x", "angle": 15, "rescale": False}),
        # 电源灯
        element([14.2, 0.1, 0.9], [14.7, 0.5, 1.05], {
            "north": {"texture": "#redstone"},
            **{k: {"texture": "#bezel"} for k in ("south", "east", "west", "up", "down")},
        }),
    ]


def tv_elements(screen_texture):
    return [
        # 机身
        element([0, 0, 13.8], [16, 16, 16], {
            "north": {"texture": "#tv_frame"},
            **{k: {"texture": "#tv_frame"} for k in ("south", "east", "west", "up", "down")},
        }),
        # 屏幕：直接铺满整格，拼接时自然连成一整块，不再有内部边框
        element([0, 0, 13.55], [16, 16, 13.8], {
            "north": {"texture": screen_texture, "uv": [0, 0, 16, 16]},
            **{k: {"texture": "#tv_frame"} for k in ("south", "east", "west", "up", "down")},
        }),
    ]


def model(elements, screen_key):
    return {
        "textures": {
            "particle": "hajikun_smart:block/chassis",
            "chassis": "hajikun_smart:block/chassis",
            "chassis_dark": "hajikun_smart:block/chassis_dark",
            "bezel": "hajikun_smart:block/bezel",
            "keyboard": "hajikun_smart:block/keyboard",
            "redstone": "hajikun_smart:block/redstone",
            "tv_frame": "hajikun_smart:block/tv_frame",
            "screen": "hajikun_smart:block/" + screen_key,
        },
        "elements": elements,
        "display": DISPLAY,
    }


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


MODELS = os.path.join(ASSETS, "models/block")
write_json(os.path.join(MODELS, "desktop_computer.json"),
           model(desktop_elements("#screen"), "screen_off"))
write_json(os.path.join(MODELS, "desktop_computer_on.json"),
           model(desktop_elements("#screen"), "screen_on"))
write_json(os.path.join(MODELS, "laptop.json"),
           model(laptop_elements("#screen"), "screen_off"))
write_json(os.path.join(MODELS, "laptop_on.json"),
           model(laptop_elements("#screen"), "screen_on"))
write_json(os.path.join(MODELS, "tv_off.json"),
           model(tv_elements("#screen"), "tv_off"))
write_json(os.path.join(MODELS, "tv_on.json"),
           model(tv_elements("#screen"), "tv_on"))
write_json(os.path.join(MODELS, "tv_cast.json"),
           model(tv_elements("#screen"), "tv_cast"))

FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}


def computer_states(name):
    variants = {}
    for facing, y in FACINGS.items():
        variants[f"facing={facing},powered=false"] = {"model": f"{MOD}:block/{name}", "y": y}
        variants[f"facing={facing},powered=true"] = {"model": f"{MOD}:block/{name}_on", "y": y}
    return {"variants": variants}


write_json(os.path.join(ASSETS, "blockstates/desktop_computer.json"), computer_states("desktop_computer"))
write_json(os.path.join(ASSETS, "blockstates/laptop.json"), computer_states("laptop"))

tv_variants = {}
for facing, y in FACINGS.items():
    for size in ("tv_2x1", "tv_5x3", "tv_16x9"):
        for master in ("true", "false"):
            for powered in ("true", "false"):
                for casting in ("true", "false"):
                    model_name = "tv_off" if powered == "false" else ("tv_cast" if casting == "true" else "tv_on")
                    tv_variants[f"casting={casting},facing={facing},master={master},powered={powered},size={size}"] = {
                        "model": f"{MOD}:block/{model_name}", "y": y}
write_json(os.path.join(ASSETS, "blockstates/tv.json"), {"variants": tv_variants})


def item_definition(model_name):
    return {"model": {"type": "minecraft:model", "model": f"{MOD}:block/{model_name}"}}


write_json(os.path.join(ASSETS, "items/desktop_computer.json"), item_definition("desktop_computer"))
write_json(os.path.join(ASSETS, "items/laptop.json"), item_definition("laptop"))
write_json(os.path.join(ASSETS, "items/tv_2x1.json"), item_definition("tv_off"))
write_json(os.path.join(ASSETS, "items/tv_5x3.json"), item_definition("tv_off"))
write_json(os.path.join(ASSETS, "items/tv_16x9.json"), item_definition("tv_off"))

write_json(os.path.join(ASSETS, "lang/zh_cn.json"), {
    "block.hajikun_smart.desktop_computer": "台式电脑",
    "block.hajikun_smart.laptop": "笔记本电脑",
    "block.hajikun_smart.tv": "智能电视",
    "item.hajikun_smart.desktop_computer": "台式电脑",
    "item.hajikun_smart.laptop": "笔记本电脑",
    "item.hajikun_smart.tv_2x1": "智能电视（2×1）",
    "item.hajikun_smart.tv_5x3": "智能电视（5×3）",
    "item.hajikun_smart.tv_16x9": "智能电视（16×9）",
    "itemGroup.hajikun_smart.devices": "哈基坤的智能设备",
})
write_json(os.path.join(ASSETS, "lang/en_us.json"), {
    "block.hajikun_smart.desktop_computer": "Desktop Computer",
    "block.hajikun_smart.laptop": "Laptop",
    "block.hajikun_smart.tv": "Smart TV",
    "item.hajikun_smart.desktop_computer": "Desktop Computer",
    "item.hajikun_smart.laptop": "Laptop",
    "item.hajikun_smart.tv_2x1": "Smart TV (2x1)",
    "item.hajikun_smart.tv_5x3": "Smart TV (5x3)",
    "item.hajikun_smart.tv_16x9": "Smart TV (16x9)",
    "itemGroup.hajikun_smart.devices": "Hajikun's Smart Devices",
})

# 台式机：一圈黑曜石 + 中心红石
write_json(os.path.join(DATA, "hajikun_smart/recipe/desktop_computer.json"), {
    "type": "minecraft:crafting_shaped",
    "key": {"#": "minecraft:obsidian", "R": "minecraft:redstone"},
    "pattern": ["###", "#R#", "###"],
    "result": {"id": "hajikun_smart:desktop_computer"},
})
# 笔记本：缺两个底角的便携版黑曜石环 + 红石
write_json(os.path.join(DATA, "hajikun_smart/recipe/laptop.json"), {
    "type": "minecraft:crafting_shaped",
    "key": {"#": "minecraft:obsidian", "R": "minecraft:redstone"},
    "pattern": ["###", "#R#", " # "],
    "result": {"id": "hajikun_smart:laptop"},
})
write_json(os.path.join(DATA, "hajikun_smart/recipe/tv_2x1.json"), {
    "type": "minecraft:crafting_shaped",
    "key": {"G": "minecraft:glass", "R": "minecraft:redstone", "I": "minecraft:iron_ingot"},
    "pattern": ["GG", "RI"],
    "result": {"id": "hajikun_smart:tv_2x1"},
})
write_json(os.path.join(DATA, "hajikun_smart/recipe/tv_5x3.json"), {
    "type": "minecraft:crafting_shaped",
    "key": {"G": "minecraft:glass", "T": "hajikun_smart:tv_2x1", "I": "minecraft:iron_ingot"},
    "pattern": ["GGG", "GTG", "III"],
    "result": {"id": "hajikun_smart:tv_5x3"},
})
write_json(os.path.join(DATA, "hajikun_smart/recipe/tv_16x9.json"), {
    "type": "minecraft:crafting_shaped",
    "key": {"O": "minecraft:obsidian", "T": "hajikun_smart:tv_5x3", "I": "minecraft:iron_ingot"},
    "pattern": ["OOO", "OTO", "III"],
    "result": {"id": "hajikun_smart:tv_16x9"},
})
write_json(os.path.join(DATA, "minecraft/tags/block/mineable/pickaxe.json"), {
    "values": [
        "hajikun_smart:desktop_computer",
        "hajikun_smart:laptop",
        "hajikun_smart:tv",
    ]
})


# ---------------------------------------------------------------- 电视大屏画面
# 多格电视由方块实体渲染器叠加一张整屏画面，按尺寸拉伸，不会一格一格重复。

def big_screen(name, painter):
    img = Image.new("RGBA", (256, 144), (0, 0, 0, 255))
    painter(img)
    img.save(os.path.join(ASSETS, "textures/block", name + ".png"))


def _blend(color, base, alpha):
    return (int(color[0] * alpha + base[0] * (1 - alpha)),
            int(color[1] * alpha + base[1] * (1 - alpha)),
            int(color[2] * alpha + base[2] * (1 - alpha)), 255)


def _tv_on(img):
    draw = ImageDraw.Draw(img)
    for y in range(144):
        t = y / 143.0
        r = int(7 + 16 * t)
        g = int(20 + 10 * t)
        b = int(46 + 32 * t)
        draw.line([(0, y), (255, y)], fill=(r, g, b, 255))
        base = (r, g, b)
        if y % 4 == 0:
            draw.line([(0, y), (255, y)], fill=_blend((0, 0, 0), base, 0.16))
    # 星点
    for _ in range(150):
        x = random.randint(0, 255)
        y = random.randint(0, 100)
        draw.point((x, y), fill=(196 + random.randint(0, 40), 214 + random.randint(0, 30), 244, 255))
    # 月亮光晕（由外向内）
    glow = [(44, 0.06), (36, 0.10), (29, 0.17), (23, 0.28), (18, 0.45)]
    for radius, alpha in glow:
        draw.ellipse([196 - radius, 34 - radius, 196 + radius, 34 + radius],
                     fill=_blend((150, 210, 240), (14, 34, 66), alpha))
    draw.ellipse([180, 18, 212, 50], fill=(226, 240, 250, 255))
    draw.ellipse([184, 22, 206, 44], fill=(202, 228, 244, 255))
    draw.ellipse([190, 30, 196, 36], fill=(190, 220, 238, 255))
    # 远山
    draw.polygon([(0, 118), (34, 92), (62, 112), (96, 86), (132, 116), (176, 96), (214, 120), (256, 104),
                  (256, 144), (0, 144)], fill=(9, 15, 32, 255))
    # 城市剪影与灯光
    for i in range(0, 256, 9):
        h = random.randint(8, 34)
        draw.rectangle([i, 144 - h, i + 6, 144], fill=(6, 10, 22, 255))
        if random.random() < 0.55:
            draw.point((i + 2, 144 - h + 3), fill=(246, 202, 116))
    # 地平线霓虹
    draw.line([(0, 120), (255, 120)], fill=(64, 158, 222))
    draw.line([(0, 121), (255, 121)], fill=(34, 96, 160))
    # 左上角状态条
    draw.rectangle([14, 12, 72, 20], fill=(22, 44, 74, 255))
    draw.rectangle([14, 12, 44, 20], fill=(96, 210, 238, 255))
    try:
        font = ImageFont.load_default(13)
    except TypeError:
        font = ImageFont.load_default()
    draw.text((14, 26), "HAJIKUN TV", font=font, fill=(224, 240, 255))
    # 暗角
    for i in range(10):
        shade = _blend((0, 0, 0), (12, 30, 60), 0.12 + i * 0.02)
        draw.rectangle([i, i, 255 - i, 143 - i], outline=shade)


def _tv_cast(img):
    draw = ImageDraw.Draw(img)
    for y in range(144):
        t = y / 143.0
        r = int(40 - 24 * t)
        g = int(18 + 24 * t)
        b = int(68 + 24 * t)
        draw.line([(0, y), (255, y)], fill=(r, g, b, 255))
        if y % 5 == 0:
            draw.line([(0, y), (255, y)], fill=_blend((0, 0, 0), (r, g, b), 0.14))
    # 中央播放键
    draw.rounded_rectangle([100, 40, 156, 96], radius=12, fill=(62, 42, 92, 255), outline=(236, 244, 255), width=2)
    draw.polygon([(118, 54), (118, 82), (142, 68)], fill=(240, 248, 255, 255))
    try:
        font = ImageFont.load_default(13)
    except TypeError:
        font = ImageFont.load_default()
    draw.text((96, 106), "CASTING", font=font, fill=(214, 232, 255))
    # 投屏弧线（左上）
    for radius in (26, 40, 54):
        draw.arc([18 - radius, 18 - radius, 18 + radius, 18 + radius], start=40, end=140,
                 fill=(130, 214, 244), width=2)
    draw.rectangle([14, 14, 24, 20], fill=(130, 214, 244))
    # 底部进度条
    draw.rectangle([28, 128, 228, 132], fill=(52, 40, 80, 255))
    draw.rectangle([28, 128, 148, 132], fill=(140, 216, 244))
    draw.ellipse([144, 126, 152, 134], fill=(240, 250, 255))


def _tv_off(img):
    draw = ImageDraw.Draw(img)
    for y in range(144):
        t = y / 143.0
        v = int(8 + 5 * t)
        draw.line([(0, y), (255, y)], fill=(v, v + 2, v + 5, 255))
        if y % 4 == 0:
            draw.line([(0, y), (255, y)], fill=(4, 5, 8, 255))
    # 斜向反光
    draw.polygon([(0, 20), (70, 0), (150, 0), (30, 60)], fill=(26, 30, 38, 255))
    draw.polygon([(0, 96), (190, 0), (230, 0), (10, 122)], fill=(19, 23, 30, 255))
    # 待机灯
    draw.ellipse([238, 128, 243, 133], fill=(186, 58, 66))


big_screen("tv_screen_on", _tv_on)
big_screen("tv_screen_cast", _tv_cast)
big_screen("tv_screen_off", _tv_off)


def icon():
    size = 128
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    radius = 22
    draw.rounded_rectangle([2, 2, 126, 126], radius=radius, fill=(18, 22, 30, 255))
    draw.rounded_rectangle([4, 4, 124, 124], radius=radius - 2, outline=(56, 72, 92, 255), width=2)
    for i in range(18):
        alpha = 46 - i * 2
        if alpha <= 0:
            break
        draw.rounded_rectangle([10 + i, 10 + i, 118 - i, 118 - i], radius=16, outline=(40, 180, 210, alpha))
    # 显示器
    draw.rounded_rectangle([22, 26, 106, 86], radius=6, fill=(30, 36, 45, 255), outline=(78, 92, 110, 255), width=2)
    draw.rounded_rectangle([28, 31, 100, 80], radius=3, fill=(8, 12, 18, 255))
    for y in range(32, 80):
        t = (y - 32) / 48.0
        color = (int(18 + 30 * t), int(70 - 30 * t), int(110 - 40 * t), 255)
        draw.line([(29, y), (99, y)], fill=color)
    for i, y in enumerate((40, 50, 60)):
        draw.rectangle([34, y, 40, y + 6], fill=[(236, 190, 84, 255), (70, 130, 220, 255), (220, 226, 236, 255)][i])
    draw.rectangle([29, 72, 99, 79], fill=(10, 16, 24, 255))
    draw.rectangle([33, 73, 39, 78], fill=(80, 196, 228, 255))
    # 支架与底座
    draw.rectangle([58, 86, 70, 98], fill=(44, 52, 64, 255))
    draw.rounded_rectangle([40, 98, 88, 104], radius=3, fill=(52, 62, 76, 255))
    # 电源键
    draw.rounded_rectangle([82, 68, 94, 78], radius=3, fill=(20, 26, 32, 255), outline=(70, 84, 100, 255))
    draw.ellipse([85, 71, 91, 77], outline=(92, 240, 160, 255), width=1)
    draw.line([(88, 70), (88, 73)], fill=(92, 240, 160, 255), width=1)
    # 键盘
    for row in range(2):
        for col in range(8):
            draw.rectangle([30 + col * 6, 106 + row * 5, 34 + col * 6, 109 + row * 5], fill=(58, 68, 82, 255))
    img.save(os.path.join(ASSETS, "icon.png"))


icon()
print("assets generated")
