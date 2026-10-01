#!/usr/bin/env python3
"""
Pretend to be the Minecraft mod so the Jak side can be tested without Minecraft.

Sends CAMERA packets (see PROTOCOL.md) to the OpenGOAL runtime at ~60 Hz, starting at the spot
where Jak was standing (Jak reports it back in JAK_STATE).

  python fake_minecraft.py look      # stand still, slowly look around 360 degrees
  python fake_minecraft.py walk      # walk a 6x6 block square, turning at the corners
  python fake_minecraft.py nod       # look up and down (checks pitch direction)
  python fake_minecraft.py fov       # sweep FOV 30..110 (checks FOV matching)
  add --blocks                       # also "place" a 5x2 wall 4 m in front (+Z) and a lava block

Expected in Jak (with the mod loaded and (mc-debug #t) in the REPL):
  Jak and Daxter disappear and the camera is at eye height where Jak stood.
  look -> view turns RIGHT (clockwise seen from above), like turning right in Minecraft
  walk -> view moves forward, then turns right at each corner and comes back to the start
  nod  -> view tilts DOWN first, then up
  --blocks -> (mc-status) shows 11 blocks; enemies walk around the wall, the one on the lava dies.
"""

import argparse
import math
import socket
import struct
import sys
import time

MAGIC = 0x4B4A434D
VERSION = 1
TYPE_CAMERA = 1
TYPE_JAK_STATE = 2
TYPE_BLOCKS = 5
FLAG_IN_WORLD = 1
FLAG_STEVE_MODE = 8
BLOCK_SOLID, BLOCK_DEADLY = 1, 2
# magic u32, version u16, type u16, seq u32, flags u32, x y z f64, yaw pitch vfov forward strafe f32, buttons u32
CAMERA_FMT = "<IHHII3d5fI"
JAK_STATE_FMT = "<IHHI8f"
assert struct.calcsize(CAMERA_FMT) == 64
assert struct.calcsize(JAK_STATE_FMT) == 44

EYE = 1.62  # Minecraft eye height


def pose(mode, t):
    """Return (x, y, z, yaw, pitch, vfov, forward, strafe, buttons) at time t seconds, relative to
    the start point (feet)."""
    x, y, z = 0.0, EYE, 0.0
    yaw, pitch, vfov, fwd, strafe, buttons = 0.0, 0.0, 70.0, 0.0, 0.0, 0
    if mode == "look":
        yaw = (t * 30.0) % 360.0  # Minecraft yaw increases when turning right
    elif mode == "nod":
        pitch = 45.0 * math.sin(t * 1.5)  # positive pitch = looking down
    elif mode == "fov":
        vfov = 70.0 + 40.0 * math.sin(t * 0.8)
    elif mode == "walk":
        side = 6.0
        speed = 2.0  # blocks per second
        d = (t * speed) % (4 * side)
        leg, along = int(d // side), d % side
        # yaw 0 = +Z, 90 = -X, 180 = -Z, 270 = +X : turning right each corner
        dirs = [(0.0, 0.0, 1.0), (90.0, -1.0, 0.0), (180.0, 0.0, -1.0), (270.0, 1.0, 0.0)]
        starts = [(0.0, 0.0), (0.0, side), (-side, side), (-side, 0.0)]
        yaw, dx, dz = dirs[leg]
        sx, sz = starts[leg]
        x, z = sx + dx * along, sz + dz * along
        fwd = 1.0
    yaw = (yaw + 180.0) % 360.0 - 180.0  # Minecraft's wrapDegrees range
    return x, y, z, yaw, pitch, vfov, fwd, strafe, buttons


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", nargs="?", default="look", choices=["look", "walk", "nod", "fov"])
    ap.add_argument("--blocks", action="store_true", help="send a small wall and a lava block")
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--hz", type=float, default=60.0)
    ap.add_argument("--seconds", type=float, default=0.0, help="stop after this long (0 = forever)")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.setblocking(False)
    dest = ("127.0.0.1", args.port)
    print(f"sending '{args.mode}' to {dest[0]}:{dest[1]} at {args.hz:.0f} Hz - Ctrl+C to stop")

    seq, t0, replies, last_reply = 0, time.time(), 0, None
    start = None  # where Jak stood (Jak meters), from JAK_STATE
    flags = FLAG_IN_WORLD | FLAG_STEVE_MODE | (1778 << 16)  # 16:9 window
    period = 1.0 / args.hz
    try:
        while True:
            t = time.time() - t0
            if args.seconds and t > args.seconds:
                break
            x, y, z, yaw, pitch, vfov, fwd, strafe, buttons = pose(args.mode, t)
            if start:
                x, y, z = x + start[0], y + start[1], z + start[2]
            pkt = struct.pack(CAMERA_FMT, MAGIC, VERSION, TYPE_CAMERA, seq, flags,
                              x, y, z, yaw, pitch, vfov, fwd, strafe, buttons)
            sock.sendto(pkt, dest)
            seq += 1
            try:
                while True:
                    data, _ = sock.recvfrom(256)
                    if len(data) >= 44:
                        magic, ver, typ, rseq, *vals = struct.unpack(JAK_STATE_FMT, data[:44])
                        if magic == MAGIC and typ == TYPE_JAK_STATE:
                            replies += 1
                            last_reply = vals
                            if start is None and int(vals[5]) & 2:
                                start = (vals[0], vals[1], vals[2])
                                print(f"\nJak handed over at {start[0]:.1f} {start[1]:.1f} {start[2]:.1f}")
            except BlockingIOError:
                pass
            if args.blocks and start and seq % int(args.hz) == 0:
                bx, by, bz = math.floor(start[0]), math.floor(start[1]), math.floor(start[2])
                blocks = [(bx + dx, by + dy, bz + 4, BLOCK_SOLID) for dx in range(-2, 3) for dy in range(2)]
                blocks.append((bx + 3, by, bz + 2, BLOCK_DEADLY))  # lava lying on Jak's floor
                payload = b"".join(struct.pack("<4i", *b) for b in blocks)
                sock.sendto(struct.pack("<IHHII", MAGIC, VERSION, TYPE_BLOCKS, seq, len(blocks)) + payload, dest)
            if seq % int(args.hz) == 0:
                sys.stdout.write(f"\rt={t:6.1f}s sent={seq} replies={replies} "
                                 f"pos=({x:6.2f},{y:6.2f},{z:6.2f}) yaw={yaw:7.1f} pitch={pitch:6.1f} fov={vfov:5.1f}  ")
                sys.stdout.flush()
            time.sleep(max(0.0, period - ((time.time() - t0) - t)))
    except KeyboardInterrupt:
        pass
    print(f"\nstopped after {seq} packets")


if __name__ == "__main__":
    main()
