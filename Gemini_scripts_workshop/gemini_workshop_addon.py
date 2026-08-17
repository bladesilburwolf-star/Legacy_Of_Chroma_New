bl_info = {
    "name": "Gemini Workshop Terrain & Engine Tools",
    "author": "Gemini Workshop",
    "version": (1, 0, 0),
    "blender": (3, 0, 0),
    "location": "View3D > Sidebar > Gemini Workshop",
    "description": "Port of Workshop JS terrain generators, normal mappers, auto-splat materials, and water systems to Blender Python.",
    "category": "3D View",
}

import bpy
import bmesh
import math
import os

# ------------------------------------------------------------------------
# Core Logic Ports (JS -> Python)
# ------------------------------------------------------------------------

def build_procedural_terrain(size_x, size_z, segments_x, segments_z, max_height):
    """Port of heightmap_generator_v3.js / index.html terrain math"""
    mesh = bpy.data.meshes.new("Gemini_Terrain_Mesh")
    obj = bpy.data.objects.new("Gemini_Terrain", mesh)
    bpy.context.collection.objects.link(obj)

    bm = bmesh.new()
    grid_verts = []

    for z in range(segments_z + 1):
        nz = z / segments_z
        world_z = (nz - 0.5) * size_z
        row = []
        for x in range(segments_x + 1):
            nx = x / segments_x
            world_x = (nx - 0.5) * size_x

            # Exact elevation formula from workshop
            dx = nx - 0.5
            dz = nz - 0.5
            dist = math.sqrt(dx * dx + dz * dz)
            raw_h = math.sin(nx * math.pi * 4) * 0.2 + math.cos(nz * math.pi * 4) * 0.2 + (0.5 - dist)
            h = max(0.0, min(1.0, raw_h))
            world_y = h * max_height

            v = bm.verts.new((world_x, world_z, world_y)) # Z-up in Blender
            row.append(v)
        grid_verts.append(row)

    bm.verts.ensure_lookup_table()

    # Create Quad Faces & UVs
    uv_layer = bm.loops.layers.uv.new("UVMap")
    for z in range(segments_z):
        for x in range(segments_x):
            v0 = grid_verts[z][x]
            v1 = grid_verts[z][x + 1]
            v2 = grid_verts[z + 1][x + 1]
            v3 = grid_verts[z + 1][x]
            face = bm.faces.new((v0, v1, v2, v3))

            # UV coordinates
            face.loops[0][uv_layer].uv = (x / segments_x, 1.0 - (z / segments_z))
            face.loops[1][uv_layer].uv = ((x + 1) / segments_x, 1.0 - (z / segments_z))
            face.loops[2][uv_layer].uv = ((x + 1) / segments_x, 1.0 - ((z + 1) / segments_z))
            face.loops[3][uv_layer].uv = (x / segments_x, 1.0 - ((z + 1) / segments_z))

    bm.to_mesh(mesh)
    bm.free()
    mesh.update()
    
    # Smooth shading
    for polygon in mesh.polygons:
        polygon.use_smooth = True

    return obj


def setup_splat_material(obj, textures_dir):
    """Port of paint_terrain.js / ShaderMaterial splat blending"""
    mat = bpy.data.materials.new(name="Gemini_Splat_Material")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links

    # Clear default nodes
    nodes.clear()

    # Material Output & BSDF
    node_out = nodes.new(type='ShaderNodeOutputMaterial')
    node_bsdf = nodes.new(type='ShaderNodeBsdfPrincipled')
    links.new(node_bsdf.outputs['BSDF'], node_out.inputs['Surface'])

    # Geometry Slope / Normal separation
    node_geom = nodes.new(type='ShaderNodeNewGeometry')
    node_separate = nodes.new(type='ShaderNodeSeparateXYZ')
    links.new(node_geom.outputs['Normal'], node_separate.inputs['Vector'])

    # Height position separation
    node_pos = nodes.new(type='ShaderNodeSeparateXYZ')
    links.new(node_geom.outputs['Position'], node_pos.inputs['Vector'])

    # Define workshop texture slots: R=Grass, G=Snow, B=Rock, A=Sand
    tex_files = {
        'Grass': os.path.join(textures_dir, 'grass1.jpg'),
        'Snow':  os.path.join(textures_dir, 'stone2.jpg'),
        'Rock':  os.path.join(textures_dir, 'rock1.jpg'),
        'Sand':  os.path.join(textures_dir, 'sand1.jpg')
    }

    tex_nodes = {}
    for key, path in tex_files.items():
        tex = nodes.new(type='ShaderNodeTexImage')
        if os.path.exists(path):
            tex.image = bpy.data.images.load(path)
        tex_nodes[key] = tex

    # Slope-based auto blend (Rock vs Grass/Sand)
    node_mix_rock = nodes.new(type='ShaderNodeMixRGB')
    links.new(node_separate.outputs['Z'], node_mix_rock.inputs['Fac']) # Normal Z = Flatness
    links.new(tex_nodes['Rock'].outputs['Color'], node_mix_rock.inputs['Color1']) # Steep = Rock
    links.new(tex_nodes['Grass'].outputs['Color'], node_mix_rock.inputs['Color2']) # Flat = Grass

    # Connect to Principled BSDF
    links.new(node_mix_rock.outputs['Color'], node_bsdf.inputs['Base Color'])

    if obj.data.materials:
        obj.data.materials[0] = mat
    else:
        obj.data.materials.append(mat)


def create_water_plane(size_x, size_z, water_y):
    """Port of water_system_v3.js"""
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0, 0, water_y))
    water = bpy.context.active_object
    water.name = "Gemini_WaterPlane"
    water.scale = (size_x, size_z, 1.0)

    # Water Material
    mat = bpy.data.materials.new(name="Gemini_Water_Material")
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf:
        bsdf.inputs['Base Color'].default_value = (0.1, 0.53, 0.6, 1.0) # Shallow Teal
        bsdf.inputs['Roughness'].default_value = 0.05
        bsdf.inputs['Transmission'].default_value = 0.85
    
    water.data.materials.append(mat)
    return water

# ------------------------------------------------------------------------
# Blender UI Panel & Operators
# ------------------------------------------------------------------------

class GEMINI_OT_GenerateTerrain(bpy.types.Operator):
    bl_idname = "gemini.generate_terrain"
    bl_label = "Generate Workshop Terrain"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        props = context.scene.gemini_props
        obj = build_procedural_terrain(props.size_x, props.size_z, props.segments, props.segments, props.max_height)
        
        # Auto texture setup if folder exists
        workshop_dir = os.path.dirname(__file__)
        setup_splat_material(obj, workshop_dir)
        
        self.report({'INFO'}, f"Generated {obj.name} cleanly!")
        return {'FINISHED'}


class GEMINI_OT_CreateWater(bpy.types.Operator):
    bl_idname = "gemini.create_water"
    bl_label = "Add Water System"
    bl_options = {'REGISTER', 'UNDO'}

    def execute(self, context):
        props = context.scene.gemini_props
        water = create_water_plane(props.size_x, props.size_z, props.water_y)
        self.report({'INFO'}, f"Added {water.name} at height Z={props.water_y}")
        return {'FINISHED'}


class GEMINI_PT_WorkshopPanel(bpy.types.Panel):
    bl_label = "Gemini Workshop Engine"
    bl_idname = "GEMINI_PT_workshop_panel"
    bl_space_type = 'VIEW_3D'
    bl_region_type = 'UI'
    bl_category = 'Gemini Workshop'

    def draw(self, context):
        layout = self.layout
        props = context.scene.gemini_props

        box = layout.box()
        box.label(text="Terrain Config", icon='GRID')
        box.prop(props, "size_x")
        box.prop(props, "size_z")
        box.prop(props, "segments")
        box.prop(props, "max_height")
        box.operator("gemini.generate_terrain", icon='LANDSCAPE')

        box_water = layout.box()
        box_water.label(text="Water System Config", icon='WATER')
        box_water.prop(props, "water_y")
        box_water.operator("gemini.create_water", icon='OUTLINER_OB_FORCE_FIELD')


class GeminiProperties(bpy.types.PropertyGroup):
    size_x: bpy.props.FloatProperty(name="Size X", default=12000.0)
    size_z: bpy.props.FloatProperty(name="Size Z", default=14000.0)
    segments: bpy.props.IntProperty(name="Resolution Grid", default=128, min=16, max=512)
    max_height: bpy.props.FloatProperty(name="Max Height", default=180.0)
    water_y: bpy.props.FloatProperty(name="Water Z-Level", default=12.0)


classes = (
    GeminiProperties,
    GEMINI_OT_GenerateTerrain,
    GEMINI_OT_CreateWater,
    GEMINI_PT_WorkshopPanel,
)

def register():
    for cls in classes:
        bpy.utils.register_class(cls)
    bpy.types.Scene.gemini_props = bpy.props.PointerProperty(type=GeminiProperties)

def unregister():
    for cls in reversed(classes):
        bpy.utils.unregister_class(cls)
    del bpy.types.Scene.gemini_props

if __name__ == "__main__":
    register()