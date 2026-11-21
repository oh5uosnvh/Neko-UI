#!/usr/bin/env python3
"""Read Android binary XML manifest without executing a target native tool."""
import struct
import zipfile

def _length(blob, offset, utf8):
    if utf8:
        n = blob[offset]; offset += 1
        if n & 0x80: n = ((n & 0x7f) << 8) | blob[offset]; offset += 1
    else:
        n = struct.unpack_from('<H', blob, offset)[0]; offset += 2
        if n & 0x8000: n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', blob, offset)[0]; offset += 2
    return n, offset

def manifest_info(apk):
    with zipfile.ZipFile(apk) as z: blob = z.read('AndroidManifest.xml')
    strings = []; info = {}; offset = struct.unpack_from('<H', blob, 2)[0]
    while offset + 8 <= len(blob):
        kind, header, size = struct.unpack_from('<HHI', blob, offset)
        if size < header or offset + size > len(blob): raise ValueError('Malformed manifest chunk')
        if kind == 0x0001:
            count, styles, flags, start, style_start = struct.unpack_from('<IIIII', blob, offset+8)
            utf8 = bool(flags & 0x100)
            for index in range(count):
                relative = struct.unpack_from('<I', blob, offset+header+4*index)[0]
                position = offset+start+relative
                _, position = _length(blob, position, utf8)
                if utf8:
                    length, position = _length(blob, position, True)
                    strings.append(blob[position:position+length].decode('utf-8'))
                else:
                    length, _ = _length(blob, offset+start+relative, False)
                    strings.append(blob[position:position+length*2].decode('utf-16le'))
        elif kind == 0x0102:
            extension = offset+16
            _, name, attr_start, attr_size, attr_count = struct.unpack_from('<IIHHH', blob, extension)
            element = strings[name]
            if element in ('manifest','uses-sdk','application'):
                for index in range(attr_count):
                    position = extension+attr_start+index*attr_size
                    _, name, raw, value_size, reserved, value_type, value = struct.unpack_from('<IIIHBBI', blob, position)
                    key = strings[name]
                    if raw != 0xffffffff: parsed = strings[raw]
                    elif value_type == 3: parsed = strings[value]
                    elif value_type == 0x12: parsed = bool(value)
                    else: parsed = value
                    info[element+'.'+key] = parsed
        offset += size
    return info

if __name__ == '__main__':
    import json,sys
    print(json.dumps(manifest_info(sys.argv[1]),ensure_ascii=False,indent=2))
