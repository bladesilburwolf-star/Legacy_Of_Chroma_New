#!/usr/bin/env python3
import math

def generate_terrain_obj(filename="terrain_land.obj"):
    # Match the engine parameters from index.html / TerrainEngine
    size_x = 12000.0
    size_z = 14000.0
    segments_x = 128
    segments_z = 128
    max_height = 180.0
    floor_y = 0.0

    vertices = []
    uvs = []
    faces = []

    # 1. Generate Vertices & UVs
    for z in range(segments_z + 1):
        nz = z / segments_z
        # Position centered around origin (0,0) matching Three.js PlaneGeometry
        world_z = (nz - 0.5) * size_z

        for x in range(segments_x + 1):
            nx = x / segments_x
            world_x = (nx - 0.5) * size_x

            # Exact height function used in index.html
            dx = nx - 0.5
            dz = nz - 0.5
            dist = math.sqrt(dx * dx + dz * dz)
            raw_h = math.sin(nx * math.pi * 4) * 0.2 + math.cos(nz * math.pi * 4) * 0.2 + (0.5 - dist)
            
            # Clamp between 0.0 and 1.0
            h = max(0.0, min(1.0, raw_h))
            world_y = floor_y + (h * max_height)

            vertices.append((world_x, world_y, world_z))
            uvs.append((nx, 1.0 - nz))  # Flip V for Blender OBJ standard

    # 2. Generate Quad/Triangle Faces (1-indexed for OBJ)
    stride = segments_x + 1
    for z in range(segments_z):
        for x in range(segments_x):
            v0 = z * stride + x + 1
            v1 = z * stride + (x + 1) + 1
            v2 = (z + 1) * stride + (x + 1) + 1
            v3 = (z + 1) * stride + x + 1

            # Two triangles per grid cell: (v0, v1, v2) and (v0, v2, v3)
            faces.append((v0, v1, v2))
            faces.append((v0, v2, v3))

    # 3. Write OBJ File
    with open(filename, "w") as f:
        f.write("# Legacy of Chroma - Terrain Mesh OBJ Export\n")
        f.write(f"# Grid: {segments_x}x{segments_z} | Dimensions: {size_x}x{size_z}\n\n")

        # Vertices
        for v in vertices:
            f.write(f"v {v[0]:.4f} {v[1]:.4f} {v[2]:.4f}\n")

        # UVs
        for uv in uvs:
            f.write(f"vt {uv[0]:.6f} {uv[1]:.6f}\n")

        # Faces (v/vt)
        for face in faces:
            f.write(f"f {face[0]}/{face[0]} {face[1]}/{face[1]} {face[2]}/{face[2]}\n")

    print(f"Successfully generated '{filename}' with {len(vertices)} vertices and {len(faces)} faces.")

if __name__ == "__main__":
    generate_terrain_obj()