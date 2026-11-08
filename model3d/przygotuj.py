# Przygotowuje model F11 z AI (ComfyUI, Pixal3D) do radia: mniej trójkątów, mniejsze tekstury,
# auto wyprostowane wzdłuż osi, przodem w zadanym kierunku, kołami na ziemi.
# blender -b -P przygotuj.py -- wejście.glb wyjście.glb [obrót_stopnie] [podgląd_z_góry.png]
import math
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector

args = sys.argv[sys.argv.index("--") + 1:]
SRC, OUT = args[0], args[1]
EXTRA_ROT = float(args[2]) if len(args) > 2 else 0.0
TOP = args[3] if len(args) > 3 else None
TARGET_TRIS = 45_000  # radio: Adreno 610, mniej trójkątów = płynniejszy obrót
TEX = 1024

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=SRC)
ob = next(o for o in bpy.context.scene.objects if o.type == "MESH")
bpy.context.view_layer.objects.active = ob
ob.select_set(True)
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

tris = sum(len(p.vertices) - 2 for p in ob.data.polygons)
dec = ob.modifiers.new("mniej", "DECIMATE")
dec.ratio = min(1.0, TARGET_TRIS / tris)
bpy.ops.object.modifier_apply(modifier=dec.name)

# oś długości auta (PCA w płaszczyźnie podłogi) -> oś X
co = np.array([v.co[:] for v in ob.data.vertices])
xy = co[:, :2] - co[:, :2].mean(axis=0)
_, vecs = np.linalg.eigh(np.cov(xy.T))
main = vecs[:, -1]
angle = -math.atan2(main[1], main[0]) + math.radians(EXTRA_ROT)
ob.data.transform(Matrix.Rotation(angle, 4, "Z"))

# na środek, koła na z = 0, długość 4.907 m jak F11
co = np.array([v.co[:] for v in ob.data.vertices])
mn, mx = co.min(axis=0), co.max(axis=0)
scale = 4.907 / (mx[0] - mn[0])
center = Vector(((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, mn[2]))
ob.data.transform(Matrix.Scale(scale, 4) @ Matrix.Translation(-center))
ob.data.update()

for img in bpy.data.images:
    if img.size[0] > TEX:
        img.scale(TEX, TEX)

bpy.ops.export_scene.gltf(filepath=OUT, export_format="GLB", export_image_format="JPEG",
                          export_jpeg_quality=85, export_yup=True)
co = np.array([v.co[:] for v in ob.data.vertices])
print("TRIS", sum(len(p.vertices) - 2 for p in ob.data.polygons), "SIZE", co.max(axis=0) - co.min(axis=0))

if TOP:
    bpy.ops.object.camera_add(location=(0, 0, 12), rotation=(0, 0, 0))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 6
    bpy.context.scene.camera = cam
    bpy.ops.object.light_add(type="SUN", location=(0, 0, 10))
    bpy.context.scene.render.engine = "BLENDER_EEVEE"
    bpy.context.scene.render.resolution_x, bpy.context.scene.render.resolution_y = 600, 600
    bpy.context.scene.render.filepath = TOP
    bpy.ops.render.render(write_still=True)
