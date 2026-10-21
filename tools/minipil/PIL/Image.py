"""A tiny stand-in for the parts of Pillow that tools/gen_textures.py uses (RGBA only), for machines without Pillow."""
import struct
import zlib


class Image:
    def __init__(self, size, color=(0, 0, 0, 0)):
        self.width, self.height = size
        if len(color) == 3:
            color = tuple(color) + (255,)
        self.px = [tuple(color)] * (self.width * self.height)

    @property
    def size(self):
        return (self.width, self.height)

    def putpixel(self, xy, color):
        x, y = xy
        if len(color) == 3:
            color = tuple(color) + (255,)
        self.px[y * self.width + x] = tuple(color)

    def getpixel(self, xy):
        x, y = xy
        return self.px[y * self.width + x]

    def paste(self, im, xy):
        ox, oy = xy
        for y in range(im.height):
            ty = oy + y
            if not 0 <= ty < self.height:
                continue
            for x in range(im.width):
                tx = ox + x
                if 0 <= tx < self.width:
                    self.px[ty * self.width + tx] = im.px[y * im.width + x]

    def rotate(self, angle):
        assert angle == 180
        out = Image(self.size)
        out.px = list(reversed(self.px))
        return out

    def save(self, path):
        raw = bytearray()
        for y in range(self.height):
            raw.append(0)
            for p in self.px[y * self.width:(y + 1) * self.width]:
                raw.extend(p)

        def chunk(kind, data):
            c = struct.pack(">I", len(data)) + kind + data
            return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

        import builtins
        with builtins.open(path, "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\n")
            f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", self.width, self.height, 8, 6, 0, 0, 0)))
            f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
            f.write(chunk(b"IEND", b""))


def new(mode, size, color=(0, 0, 0, 0)):
    assert mode == "RGBA"
    return Image(size, color)


def open(path):
    import builtins
    data = builtins.open(path, "rb").read()
    pos = 8
    idat = b""
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and interlace == 0, (depth, interlace)
        elif kind == b"IDAT":
            idat += body
        pos += 12 + n
    channels = {6: 4, 2: 3}[ctype]
    raw = zlib.decompress(idat)
    stride = w * channels
    rows = []
    prev = bytearray(stride)
    i = 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = prev[x]
            c = prev[x - channels] if x >= channels else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else b if pb <= pc else c
                line[x] = (line[x] + pr) & 255
        rows.append(line)
        prev = line
    img = Image((w, h))
    img.px = []
    for line in rows:
        for x in range(w):
            p = tuple(line[x * channels:(x + 1) * channels])
            img.px.append(p + (255,) if channels == 3 else p)
    return img
