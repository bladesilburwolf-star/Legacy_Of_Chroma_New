import os
from PIL import Image

def convert_png_to_jpg(directory="."):
    """Scans the specified directory and converts all PNG files to JPG format."""
    converted_count = 0

    print(f"Scanning '{os.path.abspath(directory)}' for PNG files...\n")

    for filename in os.listdir(directory):
        if filename.lower().endswith(".png"):
            png_path = os.path.join(directory, filename)
            jpg_filename = os.path.splitext(filename)[0] + ".jpg"
            jpg_path = os.path.join(directory, jpg_filename)

            try:
                with Image.open(png_path) as img:
                    # Convert RGBA / P modes to RGB so JPG can handle transparency properly
                    rgb_img = img.convert("RGB")
                    rgb_img.save(jpg_path, "JPEG", quality=95)
                    print(f"[✓] Converted: {filename} -> {jpg_filename}")
                    converted_count += 1
            except Exception as e:
                print(f"[X] Failed to convert {filename}: {e}")

    print(f"\nDone! Successfully converted {converted_count} PNG file(s) to JPG.")

if __name__ == "__main__":
    convert_png_to_jpg()
