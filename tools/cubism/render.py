"""Render the animated ArtMeshes evaluated by the installed Cubism Core into transparent frames."""

from pathlib import Path
from dataclasses import dataclass
import argparse
import struct
import json
import moderngl as gl
import numpy as np
from PIL import Image


class Reader:
    def __init__(self, filename):
        self.file = open(filename, "rb")

    def array(self, count, dtype):
        dt = np.dtype(dtype)
        raw = self.file.read(count * dt.itemsize)
        if len(raw) != count * dt.itemsize:
            raise EOFError("Truncated Cubism geometry stream")
        return np.frombuffer(raw, dtype=dt).astype(dt.newbyteorder("="))

    def integer(self):
        return int(self.array(1, ">i4")[0])

    def string(self):
        (n,) = struct.unpack(">H", self.file.read(2))
        return self.file.read(n).decode("utf-8")

    def end(self):
        if self.file.read(1):
            raise ValueError("Unexpected trailing geometry")
        self.file.close()


@dataclass
class Mesh:
    name: str
    atlas: int
    flags: int
    mode: int
    count: int
    uv: np.ndarray
    indices: np.ndarray
    masks: np.ndarray


def bake(model_json, geometry, out, size=512, sharp_meshes=()):
    model = json.loads(model_json.read_text(encoding="utf-8-sig"))
    s = Reader(geometry / "topology.bin")
    width, height, ox, oy, ppu = s.array(5, ">f4")
    models = []
    for _ in range(s.integer()):
        name = s.string()
        atlas, flags, mode, n = s.array(4, ">i4")
        uv = s.array(n * 2, ">f4").reshape(-1, 2)
        idx = s.array(s.integer(), ">u2")
        masks = s.array(s.integer(), ">i4")
        models.append(Mesh(name, atlas, flags, mode, n, uv, idx, masks))
    s.end()
    ctx = gl.create_standalone_context(require=330)
    vsh = """
    #version 330
    in vec2 pos; in vec2 uv;
    uniform vec2 offset; uniform float scale; out vec2 coord;
    void main(){ gl_Position=vec4(pos*scale+offset,0,1); coord=vec2(uv.x,1.0-uv.y); }
    """
    fsh = """
    #version 330
    in vec2 coord; uniform sampler2D atlas; uniform sampler2D mask;
    uniform vec3 multiplyColor,screenColor; uniform float opacity; uniform int applyMask;
    uniform float extent; uniform int useCubic; out vec4 color;
    vec4 weights(float f){
        float s=f*f, c=s*f;
        return vec4(-0.5*f+s-0.5*c,1.0-2.5*s+1.5*c,
                    0.5*f+2.0*s-1.5*c,-0.5*s+0.5*c);
    }
    vec4 readAtlas(){
        if(useCubic==0) return texture(atlas,coord);
        vec2 texel=coord*vec2(textureSize(atlas,0))-0.5;
        ivec2 origin=ivec2(floor(texel))-1;
        vec2 f=fract(texel);
        vec4 wx=weights(f.x), wy=weights(f.y), value=vec4(0.0);
        for(int j=0;j<4;j++) for(int i=0;i<4;i++)
            value+=texelFetch(atlas,origin+ivec2(i,j),0)*wx[i]*wy[j];
        value.a=clamp(value.a,0.0,1.0);
        value.rgb=clamp(value.rgb,vec3(0.0),vec3(value.a));
        return value;
    }
    void main(){
        vec4 p=readAtlas();
        vec3 c=p.rgb*multiplyColor;
        c=c+screenColor*p.a-c*screenColor;
        float m=applyMask==0?1.0:texture(mask,gl_FragCoord.xy/extent).a;
        if(applyMask==2)m=1.0-m;
        color=vec4(c,p.a)*opacity*m;
    }"""
    sh = ctx.program(vertex_shader=vsh, fragment_shader=fsh)
    maxdim = max(width, height)
    sh["offset"] = (float((ox * 2 - width) / maxdim), float((height - oy * 2) / maxdim))
    sh["scale"] = float(2 * ppu / maxdim)
    sh["atlas"] = 0
    sh["mask"] = 1
    sh["extent"] = float(size)
    textures = []
    for filename in model["FileReferences"]["Textures"]:
        p = (
            np.asarray(Image.open(model_json.parent / filename).convert("RGBA")).astype(
                "f4"
            )
            / 255.0
        )
        p[:, :, :3] *= p[:, :, 3:4]
        tex = ctx.texture((p.shape[1], p.shape[0]), 4, p.tobytes(), dtype="f4")
        tex.filter = (gl.LINEAR, gl.LINEAR)
        tex.repeat_x = False
        tex.repeat_y = False
        textures.append(tex)
    geom = []
    for mesh in models:
        if len(mesh.indices) == 0 or mesh.count == 0:
            geom.append(None)
            continue
        pos = ctx.buffer(reserve=mesh.count * 8)
        vao = ctx.vertex_array(
            sh,
            [(pos, "2f", "pos"), (ctx.buffer(mesh.uv.tobytes()), "2f", "uv")],
            ctx.buffer(mesh.indices.tobytes()),
            index_element_size=2,
        )
        geom.append((pos, vao))
    scene = ctx.simple_framebuffer((size, size), components=4, dtype="f4")
    mask_tex = ctx.texture((size, size), 4, dtype="f1")
    mask_buffer = ctx.framebuffer([mask_tex])
    white = ctx.texture((1, 1), 4, bytes([255] * 4))
    ctx.enable(gl.BLEND)
    out.mkdir(parents=True, exist_ok=True)
    s = Reader(geometry / "frames.bin")
    count = s.integer()

    def draw(i, maskmode):
        if geom[i] is None:
            return
        m = models[i]
        order, alpha, mul, screen = frame[i]
        sh["useCubic"] = int(m.name in sharp_meshes)
        sh["applyMask"] = maskmode
        sh["opacity"] = float(alpha)
        sh["multiplyColor"] = tuple(mul[:3])
        sh["screenColor"] = tuple(screen[:3])
        textures[m.atlas].use(0)
        geom[i][1].render()

    for f in range(count):
        frame = []
        for i, m in enumerate(models):
            order = s.integer()
            alpha = s.array(1, ">f4")[0]
            mul = s.array(4, ">f4")
            screen = s.array(4, ">f4")
            pos = s.array(m.count * 2, ">f4")
            if geom[i]:
                geom[i][0].write(pos.tobytes())
            frame.append((order, alpha, mul, screen))
        scene.use()
        scene.clear(0, 0, 0, 0)
        for i in sorted(range(len(models)), key=lambda n: frame[n][0]):
            m = models[i]
            if not geom[i] or frame[i][1] < 0.00001:
                continue
            masked = len(m.masks) > 0
            if masked:
                mask_buffer.use()
                mask_buffer.clear(0, 0, 0, 0)
                white.use(1)
                ctx.blend_func = (gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
                for source in m.masks:
                    draw(source, 0)
                mask_tex.use(1)
            else:
                white.use(1)
            scene.use()
            if m.flags & 1:
                ctx.blend_func = (gl.ONE, gl.ONE, gl.ZERO, gl.ONE)
            elif m.flags & 2:
                ctx.blend_func = (gl.DST_COLOR, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE)
            else:
                ctx.blend_func = (gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
            draw(i, (2 if (m.flags & 8) else 1) if masked else 0)
        data = (
            np.frombuffer(scene.read(components=4, dtype="f4"), dtype="f4")
            .reshape(size, size, 4)[::-1]
            .copy()
        )
        data[:, :, :3] /= np.maximum(data[:, :, 3:4], 1e-8)
        image = Image.fromarray(np.rint(np.clip(data, 0, 1) * 255).astype("u1"))
        image.save(out / f"{f:05}.webp", lossless=True, method=1, exact=True)
    s.end()
    ctx.release()
    print(
        f"{count} frames, {len(models)} ArtMeshes, rendered at {size}x{size}. Blend codes: {sorted(set(m.mode for m in models))}"
    )


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("model", type=Path)
    cli.add_argument("geometry", type=Path)
    cli.add_argument("out", type=Path)
    cli.add_argument("--size", type=int, default=512)
    a = cli.parse_args()
    bake(a.model, a.geometry, a.out, a.size)
