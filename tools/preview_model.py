#!/usr/bin/env python3
"""Vista previa por software del modelo de Dante (pose base) con su textura.

Replica la geometría de DanteModel.createBodyLayer() para revisar proporciones y UVs sin abrir el juego.
Uso: python3 tools/preview_model.py [salida.png]
"""
import math
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = Image.open(os.path.join(ROOT, "src/main/resources/assets/danteai/textures/entity/dante.png")).convert("RGBA")
TW, TH = TEX.size
TPX = TEX.load()

# (nombre, padre, pivot(x,y,z), rot(x,y,z), cubos[(u,v, x0,y0,z0, w,h,d)])
PARTS = [
    ("body", None, (0, 15, 1), (0, 0, 0), [(0, 0, -4, -3.5, -6, 8, 7, 12)]),
    ("collar", "body", (0, 0, 0), (0, 0, 0), [(0, 22, -4.5, -4, -6.5, 9, 6, 2)]),
    ("head", None, (0, 12.5, -5), (0, 0, 0), [(48, 0, -5, -8, -7, 10, 9, 8)]),
    ("snout", "head", (0, 0, 0), (0, 0, 0), [(84, 0, -3, -3, -11, 6, 3, 4)]),
    ("nose", "head", (0, 0, 0), (0, 0, 0), [(104, 0, -1.5, -3.5, -11.5, 3, 2, 1)]),
    ("jaw", "head", (0, 0, -7), (0.25, 0, 0), [(84, 8, -2.5, 0, -4, 5, 1, 4)]),
    ("tongue", "jaw", (0, 0, 0), (0, 0, 0), [(104, 4, -1, 0.6, -4.2, 2, 1, 3)]),
    ("left_ear", "head", (5, -7.5, -2.5), (0, 0, -0.12), [(48, 18, 0, 0, -2, 1, 7, 4)]),
    ("right_ear", "head", (-5, -7.5, -2.5), (0, 0, 0.12), [(60, 18, -1, 0, -2, 1, 7, 4)]),
    ("left_eye", "head", (0, 0, 0), (0, 0, 0), [(72, 18, 1, -6.5, -7.5, 3, 3, 1)]),
    ("right_eye", "head", (0, 0, 0), (0, 0, 0), [(72, 23, -4, -6.5, -7.5, 3, 3, 1)]),
    ("tail", None, (0, 12.5, 6.5), (0.6, 0.3, 0), [(0, 31, -1, -1, 0, 2, 2, 8)]),
    ("rfl", None, (-2.5, 18, -2.5), (0, 0, 0), [(0, 42, -1.5, 0, -1.5, 3, 6, 3)]),
    ("lfl", None, (2.5, 18, -2.5), (0, 0, 0), [(12, 42, -1.5, 0, -1.5, 3, 6, 3)]),
    ("rhl", None, (-2.5, 18, 4.5), (0, 0, 0), [(24, 42, -1.5, 0, -1.5, 3, 6, 3)]),
    ("lhl", None, (2.5, 18, 4.5), (0, 0, 0), [(36, 42, -1.5, 0, -1.5, 3, 6, 3)]),
]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def translate(x, y, z):
    return [[1, 0, 0, x], [0, 1, 0, y], [0, 0, 1, z], [0, 0, 0, 1]]


def rot_zyx(rx, ry, rz):
    # ModelPart: rotationZYX(zRot, yRot, xRot) => R = Rz * Ry * Rx
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    Rx = [[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]]
    Ry = [[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]]
    Rz = [[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    return mat_mul(Rz, mat_mul(Ry, Rx))


def apply(m, p):
    x, y, z = p
    return tuple(m[i][0] * x + m[i][1] * y + m[i][2] * z + m[i][3] for i in range(3))


def part_matrix(name):
    for n, parent, pivot, rot, _ in PARTS:
        if n == name:
            local = mat_mul(translate(*pivot), rot_zyx(*rot))
            return mat_mul(part_matrix(parent), local) if parent else local
    raise KeyError(name)


def cube_faces(u, v, x0, y0, z0, w, h, d):
    x1, y1, z1 = x0 + w, y0 + h, z0 + d
    # (vértices en orden tl, tr, br, bl según la textura, región uv)
    return [
        # frente (-Z): textura izquierda = -X
        ([(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (u + d, v + d, w, h)),
        # atrás (+Z)
        ([(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)], (u + 2 * d + w, v + d, w, h)),
        # oeste (-X): columna derecha = frente
        ([(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)], (u, v + d, d, h)),
        # este (+X): columna izquierda = frente
        ([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], (u + d + w, v + d, d, h)),
        # arriba (-Y): fila inferior = frente
        ([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (u + d, v, w, d)),
        # abajo (+Y)
        ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (u + d + w, v, w, d)),
    ]


def render(yaw_deg, pitch_deg, size=360, scale=11.0):
    img = Image.new("RGBA", (size, size), (135, 180, 235, 255))
    zbuf = [[1e9] * size for _ in range(size)]
    pix = img.load()
    yaw, pitch = math.radians(yaw_deg), math.radians(pitch_deg)
    light = (0.3, -0.8, -0.5)
    ln = math.sqrt(sum(c * c for c in light))
    light = tuple(c / ln for c in light)
    for name, parent, pivot, rot, cubes in PARTS:
        m = part_matrix(name)
        for (u, v, x0, y0, z0, w, h, d) in cubes:
            for verts, (tu, tv, tw, th) in cube_faces(u, v, x0, y0, z0, w, h, d):
                world = [apply(m, p) for p in verts]
                # cámara: girar alrededor de Y y luego inclinar
                cam = []
                for (x, y, z) in world:
                    y = y - 14
                    xr = x * math.cos(yaw) - z * math.sin(yaw)
                    zr = x * math.sin(yaw) + z * math.cos(yaw)
                    yr = y * math.cos(pitch) - zr * math.sin(pitch)
                    zr2 = y * math.sin(pitch) + zr * math.cos(pitch)
                    cam.append((xr, yr, zr2))
                a, b, c = cam[0], cam[1], cam[3]
                e1 = tuple(b[i] - a[i] for i in range(3))
                e2 = tuple(c[i] - a[i] for i in range(3))
                n = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                nl = math.sqrt(sum(k * k for k in n)) or 1
                n = tuple(k / nl for k in n)
                shade = 0.55 + 0.45 * max(0.0, -sum(n[i] * light[i] for i in range(3)))
                steps = max(2, int(max(tw, th) * scale * 1.6))
                for i in range(steps):
                    for j in range(steps):
                        s, t = (i + 0.5) / steps, (j + 0.5) / steps
                        p = tuple(a[k] + e1[k] * s + e2[k] * t for k in range(3))
                        tx = int(tu + min(tw - 1e-6, s * tw)) if tw > 0 else tu
                        ty = int(tv + min(th - 1e-6, t * th)) if th > 0 else tv
                        if not (0 <= tx < TW and 0 <= ty < TH):
                            continue
                        col = TPX[tx, ty]
                        if col[3] < 10:
                            continue
                        sx = int(size / 2 + p[0] * scale)
                        sy = int(size / 2 + p[1] * scale)
                        if 0 <= sx < size and 0 <= sy < size and p[2] < zbuf[sy][sx]:
                            zbuf[sy][sx] = p[2]
                            pix[sx, sy] = (int(col[0] * shade), int(col[1] * shade), int(col[2] * shade), 255)
    return img


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/dante_preview.png"
    views = [(0, -10), (35, -15), (90, -5), (145, -20), (180, -10), (35, -60)]
    sheet = Image.new("RGBA", (360 * 3, 360 * 2))
    for i, (yaw, pitch) in enumerate(views):
        sheet.paste(render(yaw, pitch), ((i % 3) * 360, (i // 3) * 360))
    sheet.save(out)
    print(out)
