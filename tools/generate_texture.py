#!/usr/bin/env python3
"""Genera la textura de Dante (128x64) y el ícono del mod.

La distribución UV replica la de DanteModel.createBodyLayer(): cada cubo con texOffs(u, v) y tamaño (w, h, d)
ocupa en la textura las caras estándar de Minecraft:
  arriba (u+d, v) w×d · abajo (u+d+w, v) w×d · oeste (u, v+d) d×h · frente (u+d, v+d) w×h
  este (u+d+w, v+d) d×h · atrás (u+2d+w, v+d) w×h

Uso: python3 tools/generate_texture.py
"""
import os
import random

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src/main/resources/assets/danteai/textures/entity/dante.png")
ICON = os.path.join(ROOT, "src/main/resources/assets/danteai/icon.png")

W, H = 128, 64
rng = random.Random(7)

FUR = (29, 29, 36)
FUR_TOP = (40, 40, 50)
FUR_BELLY = (20, 20, 25)
FUR_DARK = (16, 16, 20)
SNOUT = (44, 44, 52)
NOSE = (10, 10, 12)
NOSE_SHINE = (92, 92, 106)
SCLERA = (246, 246, 240)
IRIS = (112, 64, 30)
PUPIL = (22, 14, 8)
SHINE = (255, 255, 255)
LID = (78, 78, 90)
BROW = (55, 55, 66)
TONGUE = (229, 138, 154)
TONGUE_LINE = (196, 100, 120)
MOUTH = (120, 50, 62)
MOUTH_ROOF = (84, 30, 38)
COLLAR = (192, 40, 45)
COLLAR_DARK = (142, 28, 32)
TAG = (242, 194, 48)
TAG_SHINE = (255, 236, 150)
PAD = (58, 56, 66)

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
px = img.load()


def jitter(color, amount=5):
    d = rng.randint(-amount, amount)
    return tuple(max(0, min(255, c + d)) for c in color) + (255,)


def fill(x, y, w, h, color, amount=5):
    for i in range(x, x + w):
        for j in range(y, y + h):
            px[i, j] = jitter(color, amount)


def put(x, y, color):
    px[x, y] = tuple(color) + (255,)


def faces(u, v, w, h, d):
    """Rectángulos (x, y, ancho, alto) de cada cara."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "west": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "east": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def fur_cube(u, v, w, h, d, top=FUR_TOP, side=FUR, bottom=FUR_BELLY):
    f = faces(u, v, w, h, d)
    fill(*f["top"], top)
    fill(*f["bottom"], bottom)
    for k in ("west", "front", "east", "back"):
        x, y, fw, fh = f[k]
        fill(x, y, fw, fh, side)
        # Lomo un poco más claro arriba y panza más oscura abajo: da volumen con la luz de Minecraft.
        for i in range(x, x + fw):
            px[i, y] = jitter(top, 4)
            if fh > 3:
                px[i, y + fh - 1] = jitter(bottom, 3)
    return f


# --- Cuerpo (8x7x12) y collar (9x6x2).
fur_cube(0, 0, 8, 7, 12)
f = faces(0, 22, 9, 6, 2)
for k, (x, y, fw, fh) in f.items():
    fill(x, y, fw, fh, COLLAR, 6)
    if k in ("front", "back", "west", "east"):
        for i in range(x, x + fw):
            px[i, y] = jitter(COLLAR_DARK, 3)
            px[i, y + fh - 1] = jitter(COLLAR_DARK, 3)
# Chapita dorada en el frente del collar.
fx, fy, fw, fh = f["front"]
cx = fx + fw // 2 - 1
for i in range(2):
    for j in range(2):
        put(cx + i, fy + 3 + j, TAG)
put(cx, fy + 3, TAG_SHINE)

# --- Cabeza (10x9x8).
f = fur_cube(48, 0, 10, 9, 8)
x, y, fw, fh = f["front"]
# Frente: cejas, párpados cerrados (se ven al parpadear/dormir, debajo de los ojos) y frente más clara.
for i in range(x + 2, x + fw - 2):
    px[i, y + 1] = jitter(FUR_TOP, 3)
for c0 in (x + 1, x + 6):
    for i in range(c0, c0 + 3):
        put(i, y + 1, BROW)
        put(i, y + 3, LID)
    put(c0, y + 2, LID)
    put(c0 + 2, y + 2, LID)
# Mejillas apenas más claras.
for j in range(y + 5, y + 8):
    put(x, j, SNOUT)
    put(x + fw - 1, j, SNOUT)

# --- Hocico (6x3x4): un gris carbón más claro para leerse sobre la cara, con la línea de la boca.
f = faces(84, 0, 6, 3, 4)
for k, rect in f.items():
    fill(*rect, SNOUT, 4)
fill(*f["bottom"], MOUTH_ROOF, 4)
x, y, fw, fh = f["front"]
for i in range(x, x + fw):
    put(i, y + fh - 1, FUR_DARK)
put(x + fw // 2 - 1, y + 1, FUR_DARK)
put(x + fw // 2, y + 1, FUR_DARK)
for k in ("west", "east"):
    x2, y2, w2, h2 = f[k]
    for i in range(x2, x2 + w2):
        put(i, y2 + h2 - 1, FUR_DARK)

# --- Mandíbula (5x1x4): la cara de arriba es el interior de la boca.
f = faces(84, 8, 5, 1, 4)
for k, rect in f.items():
    fill(*rect, SNOUT, 4)
fill(*f["top"], MOUTH, 5)

# --- Nariz (3x2x1) brillante.
f = faces(104, 0, 3, 2, 1)
for k, rect in f.items():
    fill(*rect, NOSE, 2)
x, y, fw, fh = f["front"]
put(x, y, NOSE_SHINE)
x, y, fw, fh = f["top"]
put(x + 1, y, NOSE_SHINE)

# --- Lengua (2x1x3).
f = faces(104, 4, 2, 1, 3)
for k, rect in f.items():
    fill(*rect, TONGUE, 6)
x, y, fw, fh = f["top"]
for j in range(y, y + fh):
    put(x, j, TONGUE_LINE)

# --- Orejas caídas (1x7x4): exterior más oscuro, borde inferior redondeado.
for u in (48, 60):
    f = faces(u, 18, 1, 7, 4)
    for k, rect in f.items():
        fill(*rect, FUR_DARK, 3)
    for k in ("west", "east"):
        x, y, fw, fh = f[k]
        put(x, y + fh - 1, (0, 0, 0))
        px[x, y + fh - 1] = (0, 0, 0, 0)
        px[x + fw - 1, y + fh - 1] = (0, 0, 0, 0)

# --- Ojos (3x3x1): grandes, con iris marrón, pupila y brillo. El de la izquierda mira levemente al centro.
for v, mirror in ((18, False), (23, True)):
    f = faces(72, v, 3, 3, 1)
    for k, rect in f.items():
        fill(*rect, FUR_DARK, 2)
    x, y, fw, fh = f["front"]
    pattern = ["SSS", "IPP", "IPI"]
    colors = {"S": SCLERA, "I": IRIS, "P": PUPIL}
    for j, row in enumerate(pattern):
        if mirror:
            row = row[::-1]
        for i, ch in enumerate(row):
            put(x + i, y + j, colors[ch])
    put(x + (0 if mirror else 2), y + 1, SHINE)
    # Párpado superior oscuro.
    for i in range(fw):
        px[x + i, y] = tuple(SCLERA) + (255,)

# --- Cola de nutria (2x2x8).
fur_cube(0, 31, 2, 2, 8)

# --- Patas (3x6x3) con almohadillas.
for u in (0, 12, 24, 36):
    f = fur_cube(u, 42, 3, 6, 3, top=FUR, side=FUR, bottom=PAD)
    for k in ("west", "front", "east", "back"):
        x, y, fw, fh = f[k]
        for i in range(x, x + fw):
            px[i, y + fh - 1] = jitter(FUR_DARK, 2)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print("textura:", OUT)

# --- Ícono del mod (128x128): retrato pixel art ampliado.
icon_px = [
    "................",
    "...KK......KK...",
    "..KKKKKKKKKKKK..",
    "..KKKKKKKKKKKK..",
    ".KKKKKKKKKKKKKK.",
    ".KKKSSKKKKSSKKK.",
    ".KKKIPKKKKPIKKK.",
    ".KKKKKKKKKKKKKK.",
    ".KKKKKGGGGKKKKK.",
    "..KKKGGNNGGKKK..",
    "..KKKGGGGGGKKK..",
    "...KKGGPPGGKK...",
    "....KKKPPKKK....",
    ".....RRRRRR.....",
    "......RYYR......",
    "................",
]
pal = {"K": FUR, "S": SCLERA, "I": IRIS, "P": TONGUE, "G": SNOUT, "N": NOSE, "R": COLLAR, "Y": TAG}
pal["P"] = PUPIL
icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
ip = icon.load()
for yy, row in enumerate(icon_px):
    for xx, ch in enumerate(row):
        if ch in pal:
            ip[xx, yy] = tuple(pal[ch]) + (255,)
ip[7, 11] = tuple(TONGUE) + (255,)
ip[8, 11] = tuple(TONGUE) + (255,)
ip[7, 12] = tuple(TONGUE) + (255,)
ip[8, 12] = tuple(TONGUE) + (255,)
icon.resize((128, 128), Image.NEAREST).save(ICON)
print("ícono:", ICON)
