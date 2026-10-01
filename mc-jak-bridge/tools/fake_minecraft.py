#!/usr/bin/env python3
"""
Pretend to be the Minecraft mod so the Jak side can be tested without Minecraft.

Sends CAMERA packets (see PROTOCOL.md) to the OpenGOAL runtime at ~60 Hz.

  python fake_minecraft.py look      # stand still, slowly look around 360 degrees
  python fake_minecraft.py walk      # walk a 6x6 block square, turning at the corners
  python fake_minecraft.py nod       # look up and down (checks pitch direction)
  python fake_minecraft.py fov       # sweep FOV 30..110 (checks FOV matching)
  python fake_minecraft.py body      # BODY mode: hold W, turn right 90 deg every 3 s, jump now and then

Expected in Jak (with the mod loaded and (mc-debug #t) in the REPL):
  look -> view turns RIGHT (clockwise seen from above), like turning right in Minecraft
  walk -> view moves forward, then turns right at each corner and comes back to the start
  nod  -> view tilts DOWN first, then up
  body -> first person from Jak's head: Jak runs forward (walls stop you), jumps, and turns right;
          the script prints the Minecraft position Jak sends back.
If left/right is backwards, run (set! (-> *mc-passthrough* mirror-x?) #t) and (mc-reanchor).
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
FLAG_IN_WORLD = 1
FLAG_BODY_MODE = 4
BUTTON_JUMP = 1
# magic u32, version u16, type u16, seq u32, flags u32, x y z f64, yaw pitch vfov forward strafe f32, buttons u32
CAMERA_FMT = "<IHHII3d5fI"
JAK_STATE_FMT = "<IHHI8f"
assert struct.calcsize(CAMERA_FMT) == 64
assert struct.calcsize(JAK_STATE_FMT) == 44

EYE = 1.62  # Minecraft eye height


def pose(mode, t):
    """Return (x, y, z, yaw, pitch, vfov, forward, strafe, buttons) at time t seconds."""
    x, y, z = 0.0, 64.0 + EYE, 0.0
    yaw, pitch, vfov, fwd, strafe, buttons = 0.0, 0.0, 70.0, 0.0, 0.0, 0
    if mode == "body":
        yaw = 90.0 * int(t // 3.0)          # snap-turn right every 3 seconds
        pitch = 10.0                        # look slightly down
        fwd = 1.0                           # hold W
        if (t % 3.0) > 2.5:
            buttons |= BUTTON_JUMP          # jump near the end of each leg
    elif mode == "look":
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
    ap.add_argument("mode", nargs="?", default="look", choices=["look", "walk", "nod", "fov", "body"])
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
    flags = FLAG_IN_WORLD | (FLAG_BODY_MODE if args.mode == "body" else 0)
    period = 1.0 / args.hz
    try:
        while True:
            t = time.time() - t0
            if args.seconds and t > args.seconds:
                break
            x, y, z, yaw, pitch, vfov, fwd, strafe, buttons = pose(args.mode, t)
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
            except BlockingIOError:
                pass
            if seq % int(args.hz) == 0:
                if args.mode == "body" and last_reply:
                    jx, jy, jz, _, health, jflags = last_reply[:6]
                    sys.stdout.write(f"\rt={t:6.1f}s sent={seq} replies={replies} yaw={yaw:6.1f} "
                                     f"jak->mc eye=({jx:7.2f},{jy:7.2f},{jz:7.2f}) health={health:.0f} body={int(jflags) & 1}  ")
                else:
                    sys.stdout.write(f"\rt={t:6.1f}s sent={seq} replies={replies} "
                                     f"pos=({x:6.2f},{y:6.2f},{z:6.2f}) yaw={yaw:7.1f} pitch={pitch:6.1f} fov={vfov:5.1f}  ")
                sys.stdout.flush()
            time.sleep(max(0.0, period - ((time.time() - t0) - t)))
    except KeyboardInterrupt:
        pass
    print(f"\nstopped after {seq} packets")


if __name__ == "__main__":
    main()
