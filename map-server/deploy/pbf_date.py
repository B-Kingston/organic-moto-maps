#!/usr/bin/env python3
"""Print the YYMMDD date of an OSM PBF extract's data.

Geofabrik names dated extracts by the UTC date of the OSM data they contain,
which is the `osmosis_replication_timestamp` in the file's first OSMHeader
blob (verified against queensland-260928.osm.pbf: header 2026-09-28T20:23Z,
name 260928). Deriving the catalog date from the bytes themselves keeps a
local-path catalog entry honest even when the file's name says otherwise.

Usage: pbf_date.py FILE

Exits non-zero when the file is not a PBF or carries no replication
timestamp; callers should then require an explicit date.
"""

import datetime
import struct
import sys
import zlib


def read_varint(buf, i):
    shift = 0
    value = 0
    while True:
        b = buf[i]
        i += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, i
        shift += 7


def fields(buf):
    i = 0
    out = {}
    while i < len(buf):
        key, i = read_varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(buf, i)
            out.setdefault(field, []).append(value)
        elif wire == 2:
            length, i = read_varint(buf, i)
            out.setdefault(field, []).append(buf[i : i + length])
            i += length
        elif wire == 5:
            i += 4
        elif wire == 1:
            i += 8
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
    return out


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    path = sys.argv[1]
    with open(path, "rb") as f:
        raw = f.read(4)
        if len(raw) != 4:
            raise ValueError("file is too small to be a PBF")
        header_len = struct.unpack(">I", raw)[0]
        if header_len == 0 or header_len > 64 * 1024:
            raise ValueError(f"invalid PBF blob header length {header_len}")
        header = fields(f.read(header_len))
        if header.get(1, [b""])[0] != b"OSMHeader":
            raise ValueError("first PBF blob is not an OSMHeader")
        blob = fields(f.read(header.get(3, [0])[0]))
        if 1 in blob:
            block = blob[1][0]
        elif 3 in blob:
            block = zlib.decompress(blob[3][0])
        else:
            raise ValueError("OSMHeader blob has no raw or zlib payload")
        header_block = fields(block)
        timestamp = header_block.get(32, [None])[0]
        if not timestamp:
            raise ValueError("OSMHeader has no osmosis_replication_timestamp")
    date = datetime.datetime.fromtimestamp(timestamp, datetime.timezone.utc)
    print(date.strftime("%y%m%d"))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - CLI: report and exit 1
        print(f"pbf_date.py: {exc}", file=sys.stderr)
        sys.exit(1)
