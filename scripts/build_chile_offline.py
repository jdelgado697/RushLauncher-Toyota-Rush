#!/usr/bin/env python3
"""Crea mapa nacional sin conexion desde el extracto .osm.pbf de Geofabrik.
Mapas © contribuidores OpenStreetMap (ODbL).
No descarga NI archiva tiles raster de tile.openstreetmap.org.
El resultado solo contiene geometria vial, agua, areas verdes y poblaciones
para un modo offline simplificado. No es una base de enrutamiento sin internet.
"""
import argparse
import math
import os
import sqlite3
import struct
import sys
import time
import zlib
from pathlib import Path

try:
    import osmium
except ImportError as exc:
    sys.exit("Instalar dependencia: pip install osmium: " + str(exc))

ROAD_CLASS = {
    "motorway": 0, "motorway_link": 0,
    "trunk": 1, "trunk_link": 1,
    "primary": 2, "primary_link": 2,
    "secondary": 3, "secondary_link": 3,
    "tertiary": 4, "tertiary_link": 4,
    "residential": 5, "unclassified": 6, "road": 6,
    "living_street": 6, "service": 7, "pedestrian": 7,
    "track": 8, "busway": 7,
}
PLACE_CLASS = {
    "city": 0, "town": 1, "village": 2, "hamlet": 3,
    "suburb": 3, "locality": 4,
}

class Chile(osmium.SimpleHandler):
    def __init__(self, con):
        super().__init__()
        self.con = con
        self.road_buf = []
        self.box_buf = []
        self.place_buf = []
        self.total_ways = 0
        self.total_places = 0
        self.next_id = 1

    def way(self, w):
        tags = w.tags
        kind = ROAD_CLASS.get(tags.get("highway"))
        if kind is None:
            water = tags.get("natural") in ("water", "bay") or tags.get("waterway") in ("river", "stream", "canal")
            green = tags.get("landuse") in ("forest", "grass", "meadow") or tags.get("leisure") in ("park", "garden")
            coast = tags.get("natural") == "coastline"
            if water: kind = -3
            elif green: kind = -2
            elif coast: kind = -1
            else: return
        coords = []
        minlat = 99999999
        maxlat = -99999999
        minlon = 99999999
        maxlon = -99999999
        for n in w.nodes:
            try:
                if not n.location.valid():continue
                lat = round(n.location.lat * 1000000)
                lon = round(n.location.lon * 1000000)
            except (osmium.InvalidLocationError, ValueError):
                continue
            # Latitudes validas: Arica a Cabo de Hornos, incluyendo islas.
            if abs(lat) > 85000000 or abs(lon) > 180000000:continue
            if coords and coords[-1] == (lon,lat):continue
            coords.append((lon,lat))
            minlat=min(minlat,lat); maxlat=max(maxlat,lat)
            minlon=min(minlon,lon); maxlon=max(maxlon,lon)
        if len(coords)<2:return
        if kind in (-3,-2) and len(coords)<4:return
        b = bytearray()
        for lon,lat in coords:
            b.extend(struct.pack("<ii",lon,lat))
        raw = bytes(b)
        compressed = zlib.compress(raw,level=4)
        payload = (b"\1"+compressed) if len(compressed)+1<len(raw) else (b"\0"+raw)
        name = (tags.get("name") or tags.get("ref") or "")[:72]
        fid = self.next_id
        self.next_id += 1
        self.road_buf.append((fid,kind,name,payload))
        self.box_buf.append((fid,minlon/1e6,maxlon/1e6,minlat/1e6,maxlat/1e6))
        self.total_ways += 1
        if len(self.road_buf)>=3000:
            self.flush_ways()

    def node(self, n):
        ptype=PLACE_CLASS.get(n.tags.get("place"))
        if ptype is None:return
        name=n.tags.get("name")
        if not name:return
        try:
            if not n.location.valid():return
            lat=n.location.lat; lon=n.location.lon
        except (osmium.InvalidLocationError,ValueError):return
        self.place_buf.append((round(lon,6),round(lat,6),ptype,name[:72]))
        self.total_places += 1
        if len(self.place_buf)>=3000:self.flush_places()

    def flush_ways(self):
        if not self.road_buf:return
        self.con.executemany("INSERT INTO roads VALUES(?,?,?,?)",self.road_buf)
        self.con.executemany("INSERT INTO bounds VALUES(?,?,?,?,?)",self.box_buf)
        self.road_buf.clear()
        self.box_buf.clear()

    def flush_places(self):
        if not self.place_buf:return
        self.con.executemany("INSERT INTO places(lon,lat,kind,name) VALUES(?,?,?,?)",self.place_buf)
        self.place_buf.clear()

def main():
    p=argparse.ArgumentParser()
    p.add_argument("source")
    p.add_argument("output")
    args=p.parse_args()
    db=Path(args.output)
    db.parent.mkdir(parents=True,exist_ok=True)
    if db.exists():db.unlink()
    start=time.time()
    con=sqlite3.connect(str(db))
    con.executescript("""
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
PRAGMA temp_store=MEMORY;
PRAGMA cache_size=-120000;
CREATE TABLE roads(id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT, geom BLOB NOT NULL);
CREATE VIRTUAL TABLE bounds USING rtree(id,minx,maxx,miny,maxy);
CREATE TABLE places(lon REAL,lat REAL,kind INTEGER,name TEXT);
CREATE INDEX places_xy ON places(lon,lat);
""")
    handler=Chile(con)
    # flex_mem avoids hundreds of temporary disk writes; standard Github runner
    # has enough memory for Chile's full Geofabrik extract.
    handler.apply_file(args.source,locations=True,idx="flex_mem")
    handler.flush_ways()
    handler.flush_places()
    con.commit()
    con.executescript("ANALYZE; VACUUM; PRAGMA optimize;")
    count=con.execute("SELECT COUNT(*) FROM roads").fetchone()[0]
    count_place=con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    con.close()
    size=db.stat().st_size
    print("Offline Chile from OSM: roads=%d places=%d bytes=%d seconds=%.1f" %
          (count,count_place,size,time.time()-start),flush=True)
    if count<20000 or count_place<200 or size<2_000_000:
        sys.exit("Extracto offline incompleto; rechazando APK")

if __name__=="__main__":main()
