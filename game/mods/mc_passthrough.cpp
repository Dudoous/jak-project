/*!
 * @file mc_passthrough.cpp
 * Minecraft passthrough bridge: localhost UDP listener + GOAL-callable accessors.
 */

#include "mc_passthrough.h"

// clang-format off
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <WinSock2.h>
#include <WS2tcpip.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#endif
// clang-format on

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

#include "common/log/log.h"

#include "game/kernel/common/Ptr.h"

namespace mc_passthrough {
namespace {

#ifdef _WIN32
using sock_t = SOCKET;
constexpr sock_t kBadSock = INVALID_SOCKET;
using addrlen_t = int;
void close_sock(sock_t s) {
  closesocket(s);
}
#else
using sock_t = int;
constexpr sock_t kBadSock = -1;
using addrlen_t = socklen_t;
void close_sock(sock_t s) {
  close(s);
}
#endif

using clock_type = std::chrono::steady_clock;

#pragma pack(push, 1)
struct CameraPacket {
  u32 magic;
  u16 version;
  u16 type;
  u32 seq;
  u32 flags;
  double x, y, z;
  float yaw, pitch, vfov;
  float forward, strafe;
  u32 buttons;
  // since bridge 0.11 (80-byte packets): where Steve's feet are, which is not under the camera in
  // third person view
  float feet[3];
  u32 body_flags;  // 1 = feet valid, 2 = look valid
  // since bridge 0.12 (88-byte packets): where Steve himself looks (degrees). The camera looks the
  // other way in Minecraft's front view, and Steve's punches go where he looks.
  float look_yaw, look_pitch;
};

struct EventPacket {
  u32 magic;
  u16 version;
  u16 type;
  u32 seq;
  u32 event;      // kEventExplosion, ...
  float data[5];  // explosion: x, y, z (Jak meters), radius (m), power
};

struct JakStatePacket {
  u32 magic;
  u16 version;
  u16 type;
  u32 seq;
  float data[kJakStateFloats];
};
#pragma pack(pop)

static_assert(sizeof(CameraPacket) == 88, "CameraPacket layout changed - update PROTOCOL.md");
constexpr int kMinCameraPacket = 64;  // older bridges send no feet / look direction
static_assert(sizeof(JakStatePacket) == 56, "JakStatePacket layout changed - update PROTOCOL.md");
static_assert(sizeof(EventPacket) == 36, "EventPacket layout changed - update PROTOCOL.md");

// Minecraft -> Jak: blocks the player built near Steve (x, y, z, kind)
s32 g_blocks[kMaxBlocks * 4];
int g_block_count = 0;

// Minecraft -> Jak gameplay events, consumed by GOAL with (pc-mc-pop-event ...)
constexpr int kMaxQueuedEvents = 64;
EventPacket g_events[kMaxQueuedEvents];
int g_event_head = 0;  // next to pop
int g_event_count = 0;

std::mutex g_mutex;
CameraPacket g_latest{};
bool g_have_packet = false;
clock_type::time_point g_latest_time{};
sockaddr_in g_client{};
bool g_have_client = false;
u32 g_send_seq = 0;

// camera pose bookkeeping (see goal_pose_used)
FramePose g_last_read;   // last packet GOAL read with pc-mc-get-camera
FramePose g_pending;     // pose of the frame GOAL is building
FramePose g_chain_pose;  // pose of the frame handed to the renderer

std::atomic<bool> g_running{false};
sock_t g_sock = kBadSock;

void listen_loop() {
  static char buf[65536];  // BLOCKS packets can be ~32 KB
  while (g_running.load()) {
    sockaddr_in from{};
    addrlen_t from_len = sizeof(from);
    int n = recvfrom(g_sock, buf, (int)sizeof(buf), 0, (sockaddr*)&from, &from_len);
    if (n >= 16) {
      u32 magic;
      u16 version, type;
      memcpy(&magic, buf, 4);
      memcpy(&version, buf + 4, 2);
      memcpy(&type, buf + 6, 2);
      if (magic == kMagic && version == kVersion && type == kTypeBlocks) {
        u32 count;
        memcpy(&count, buf + 12, 4);
        if (count > (u32)kMaxBlocks) {
          count = kMaxBlocks;
        }
        if (16 + (int)count * 16 <= n) {
          std::lock_guard<std::mutex> lk(g_mutex);
          memcpy(g_blocks, buf + 16, count * 16);
          g_block_count = (int)count;
        }
        continue;
      }
    }
    if (n >= (int)sizeof(EventPacket)) {
      EventPacket ev;
      memcpy(&ev, buf, sizeof(ev));
      if (ev.magic == kMagic && ev.version == kVersion && ev.type == kTypeEvent) {
        std::lock_guard<std::mutex> lk(g_mutex);
        if (g_event_count < kMaxQueuedEvents) {
          g_events[(g_event_head + g_event_count) % kMaxQueuedEvents] = ev;
          g_event_count++;
        }
        continue;
      }
    }
    if (n < kMinCameraPacket) {
      continue;  // timeout, error, or runt packet
    }
    CameraPacket pkt{};
    memcpy(&pkt, buf, n < (int)sizeof(pkt) ? (size_t)n : sizeof(pkt));
    if (pkt.magic != kMagic || pkt.version != kVersion || pkt.type != kTypeCamera) {
      continue;
    }
    std::lock_guard<std::mutex> lk(g_mutex);
    g_latest = pkt;
    g_have_packet = true;
    g_latest_time = clock_type::now();
    g_client = from;
    g_have_client = true;
  }
}

}  // namespace

void start() {
  if (g_running.load()) {
    return;
  }

  int port = kDefaultPort;
  if (const char* env = std::getenv("OPENGOAL_MC_PORT")) {
    int p = std::atoi(env);
    if (p > 0 && p < 65536) {
      port = p;
    }
  }

#ifdef _WIN32
  WSADATA wsa_data{};
  if (WSAStartup(MAKEWORD(2, 2), &wsa_data) != 0) {
    lg::error("[mc-passthrough] WSAStartup failed");
    return;
  }
#endif

  g_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (g_sock == kBadSock) {
    lg::error("[mc-passthrough] failed to create UDP socket");
    return;
  }

  // short receive timeout so the thread can notice shutdown
#ifdef _WIN32
  DWORD timeout_ms = 100;
  setsockopt(g_sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeout_ms, sizeof(timeout_ms));
#else
  timeval tv{};
  tv.tv_sec = 0;
  tv.tv_usec = 100000;
  setsockopt(g_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
#endif

  sockaddr_in addr{};
  addr.sin_family = AF_INET;
  addr.sin_port = htons((u16)port);
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // localhost only, never exposed to the network
  if (bind(g_sock, (sockaddr*)&addr, sizeof(addr)) != 0) {
    lg::error("[mc-passthrough] failed to bind UDP 127.0.0.1:{} (is another game instance open?)",
              port);
    close_sock(g_sock);
    g_sock = kBadSock;
    return;
  }

  g_running.store(true);
  // detached so that an abrupt process exit can't hit std::terminate on a joinable thread.
  std::thread(listen_loop).detach();
  lg::info("[mc-passthrough] listening for Minecraft on UDP 127.0.0.1:{}", port);
}

void stop() {
  if (!g_running.exchange(false)) {
    return;
  }
  // the listener wakes up at least every 100 ms (SO_RCVTIMEO); give it time to exit.
  std::this_thread::sleep_for(std::chrono::milliseconds(250));
  close_sock(g_sock);
  g_sock = kBadSock;
}

u64 goal_get_camera(u32 dst) {
  float* out = Ptr<float>(dst).c();
  std::lock_guard<std::mutex> lk(g_mutex);
  if (!g_have_packet) {
    for (int i = 0; i < kCameraFloats; i++) {
      out[i] = 0.f;
    }
    out[6] = 1.0e9f;  // "infinitely old"
    return 0;
  }
  auto age = std::chrono::duration<float, std::milli>(clock_type::now() - g_latest_time).count();
  out[0] = (float)g_latest.x;
  out[1] = (float)g_latest.y;
  out[2] = (float)g_latest.z;
  out[3] = g_latest.yaw;
  out[4] = g_latest.pitch;
  out[5] = g_latest.vfov;
  out[6] = age;
  out[7] = (float)g_latest.seq;
  out[8] = (float)g_latest.flags;
  out[9] = g_latest.forward;
  out[10] = g_latest.strafe;
  out[11] = (float)g_latest.buttons;
  out[12] = g_latest.feet[0];
  out[13] = g_latest.feet[1];
  out[14] = g_latest.feet[2];
  out[15] = (g_latest.body_flags & 1) ? 1.f : 0.f;
  out[16] = g_latest.look_yaw;
  out[17] = g_latest.look_pitch;
  out[18] = (g_latest.body_flags & 2) ? 1.f : 0.f;
  out[19] = 0.f;
  g_last_read.valid = 1;
  g_last_read.seq = g_latest.seq;
  g_last_read.x = out[0];
  g_last_read.y = out[1];
  g_last_read.z = out[2];
  g_last_read.yaw = out[3];
  g_last_read.pitch = out[4];
  g_last_read.vfov = out[5];
  return 1;
}

u64 goal_pop_event(u32 dst) {
  float* out = Ptr<float>(dst).c();
  std::lock_guard<std::mutex> lk(g_mutex);
  if (g_event_count == 0) {
    return 0;
  }
  const EventPacket& ev = g_events[g_event_head];
  for (int i = 0; i < 5; i++) {
    out[i] = ev.data[i];
  }
  u64 type = ev.event;
  g_event_head = (g_event_head + 1) % kMaxQueuedEvents;
  g_event_count--;
  return type;
}

u64 goal_get_blocks(u32 dst, u32 max) {
  s32* out = Ptr<s32>(dst).c();
  std::lock_guard<std::mutex> lk(g_mutex);
  int n = g_block_count;
  if (n > (int)max) {
    n = (int)max;
  }
  memcpy(out, g_blocks, (size_t)n * 16);
  return (u64)n;
}

u64 goal_send_actors(u32 src, u32 count) {
  const float* in = Ptr<float>(src).c();
  if (count > (u32)kMaxActors) {
    count = kMaxActors;
  }
  // header: magic u32, version u16, type u16, seq u32, count u32; then 32 bytes per object
  u8 buf[16 + kMaxActors * 32];
  sockaddr_in client;
  u32 seq;
  {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_have_client || g_sock == kBadSock) {
      return 0;
    }
    client = g_client;
    seq = g_send_seq++;
  }
  const u32 magic = kMagic;
  const u16 version = kVersion, type = kTypeActors;
  memcpy(buf, &magic, 4);
  memcpy(buf + 4, &version, 2);
  memcpy(buf + 6, &type, 2);
  memcpy(buf + 8, &seq, 4);
  memcpy(buf + 12, &count, 4);
  for (u32 i = 0; i < count; i++) {
    const float* a = in + i * 8;
    u8* out = buf + 16 + i * 32;
    const u32 id = (u32)a[0];
    const u32 kind = (u32)a[1];
    memcpy(out, &id, 4);
    memcpy(out + 4, &kind, 4);
    memcpy(out + 8, a + 2, 6 * 4);  // x y z half nx nz
  }
  const int len = (int)(16 + count * 32);
  int n = sendto(g_sock, (const char*)buf, len, 0, (const sockaddr*)&client, (int)sizeof(client));
  return n == len ? 1 : 0;
}

u64 goal_pose_used() {
  std::lock_guard<std::mutex> lk(g_mutex);
  g_pending = g_last_read;
  return g_pending.valid;
}

void on_chain_sent() {
  std::lock_guard<std::mutex> lk(g_mutex);
  g_chain_pose = g_pending;
  g_pending.valid = 0;  // a frame where GOAL didn't drive the camera has no Minecraft pose
}

FramePose rendered_pose() {
  std::lock_guard<std::mutex> lk(g_mutex);
  return g_chain_pose;
}

bool steve_mode_active() {
  std::lock_guard<std::mutex> lk(g_mutex);
  if (!g_have_packet || !(g_latest.flags & kFlagSteveMode)) {
    return false;
  }
  return clock_type::now() - g_latest_time < std::chrono::milliseconds(1500);
}

u64 goal_send_light(u32 src) {
  const float* in = Ptr<float>(src).c();
  // header: magic u32, version u16, type u16, seq u32; then the floats
  u8 buf[12 + kLightFloats * 4];
  sockaddr_in client;
  u32 seq;
  {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_have_client || g_sock == kBadSock) {
      return 0;
    }
    client = g_client;
    seq = g_send_seq++;
  }
  const u32 magic = kMagic;
  const u16 version = kVersion, type = kTypeLight;
  memcpy(buf, &magic, 4);
  memcpy(buf + 4, &version, 2);
  memcpy(buf + 6, &type, 2);
  memcpy(buf + 8, &seq, 4);
  memcpy(buf + 12, in, kLightFloats * 4);
  int n = sendto(g_sock, (const char*)buf, (int)sizeof(buf), 0, (const sockaddr*)&client,
                 (int)sizeof(client));
  return n == (int)sizeof(buf) ? 1 : 0;
}

u64 goal_send_state(u32 src) {
  const float* in = Ptr<float>(src).c();
  sockaddr_in client;
  JakStatePacket pkt{};
  {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_have_client || g_sock == kBadSock) {
      return 0;
    }
    client = g_client;
    pkt.seq = g_send_seq++;
  }
  pkt.magic = kMagic;
  pkt.version = kVersion;
  pkt.type = kTypeJakState;
  memcpy(pkt.data, in, sizeof(pkt.data));
  int n = sendto(g_sock, (const char*)&pkt, (int)sizeof(pkt), 0, (const sockaddr*)&client,
                 (int)sizeof(client));
  return n == (int)sizeof(pkt) ? 1 : 0;
}

}  // namespace mc_passthrough
