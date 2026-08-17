#!/usr/bin/env python3
"""
MAP_CRAFT Python CLI — Modern ShaderMap alternative
Usage:
    python3 map_craft_v1.py grass1.jpg --strength 3.0 --ao_rad 4
"""

import argparse
import os
import numpy as np
from PIL import Image

def generate_maps(input_path, strength=2.5, ao_radius=3):
    if not os.path.exists(input_path):
        print(f"[ERR] File not found: {input_path}")
        return

    base_name, ext = os.path.splitext(input_path)
    img = Image.open(input_path).convert('RGB')
    arr = np.array(img, dtype=np.float32)

    # 1. Heightmap (Luminance)
    lum = (arr[:, :, 0] * 0.299 + arr[:, :, 1] * 0.587 + arr[:, :, 2] * 0.114) / 255.0
    height_img = Image.fromarray((lum * 255).astype(np.uint8))
    height_img.save(f"{base_name}_height.png")
    print(f"[+] Saved: {base_name}_height.png")

    # 2. Tangent-Space Normal Map
    gy, gx = np.gradient(lum)
    gx *= strength
    gy *= strength

    normal_x = -gx
    normal_y = -gy
    normal_z = np.ones_like(lum)

    norm = np.sqrt(normal_x**2 + normal_y**2 + normal_z**2)
    normal_x /= norm
    normal_y /= norm
    normal_z /= norm

    normal_rgb = np.zeros((*lum.shape, 3), dtype=np.uint8)
    normal_rgb[:, :, 0] = ((normal_x * 0.5 + 0.5) * 255).astype(np.uint8)
    normal_rgb[:, :, 1] = ((normal_y * 0.5 + 0.5) * 255).astype(np.uint8)
    normal_rgb[:, :, 2] = ((normal_z * 0.5 + 0.5) * 255).astype(np.uint8)

    norm_img = Image.fromarray(normal_rgb)
    norm_img.save(f"{base_name}_normal.png")
    print(f"[+] Saved: {base_name}_normal.png")

    # 3. Roughness Map
    rough = np.clip((1.0 - lum) * 1.2 * 255, 0, 255).astype(np.uint8)
    rough_img = Image.fromarray(rough)
    rough_img.save(f"{base_name}_roughness.png")
    print(f"[+] Saved: {base_name}_roughness.png")

    print("[SUCCESS] All map passes baked successfully!")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="MapCraft - ShaderMap 4 CLI Alternative")
    parser.add_argument("input", help="Path to source albedo/color texture")
    parser.add_argument("--strength", type=float, default=2.5, help="Normal map depth intensity")
    parser.add_argument("--ao_rad", type=int, default=3, help="Ambient Occlusion sample radius")

    args = parser.parse_args()
    generate_maps(args.input, args.strength, args.ao_rad)