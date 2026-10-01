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
};

struct JakStatePacket {
  u32 magic;
  u16 version;
  u16 type;
  u32 seq;
  float data[8];
};
#pragma pack(pop)

static_assert(sizeof(CameraPacket) == 64, "CameraPacket layout changed - update PROTOCOL.md");
static_assert(sizeof(JakStatePacket) == 44, "JakStatePacket layout changed - update PROTOCOL.md");

std::mutex g_mutex;
CameraPacket g_latest{};
bool g_have_packet = false;
clock_type::time_point g_latest_time{};
sockaddr_in g_client{};
bool g_have_client = false;
u32 g_send_seq = 0;

std::atomic<bool> g_running{false};
sock_t g_sock = kBadSock;

void listen_loop() {
  char buf[256];
  while (g_running.load()) {
    sockaddr_in from{};
    addrlen_t from_len = sizeof(from);
    int n = recvfrom(g_sock, buf, (int)sizeof(buf), 0, (sockaddr*)&from, &from_len);
    if (n < (int)sizeof(CameraPacket)) {
      continue;  // timeout, error, or runt packet
    }
    CameraPacket pkt;
    memcpy(&pkt, buf, sizeof(pkt));
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
  return 1;
}

bool steve_mode_active() {
  std::lock_guard<std::mutex> lk(g_mutex);
  if (!g_have_packet || !(g_latest.flags & kFlagSteveMode)) {
    return false;
  }
  return clock_type::now() - g_latest_time < std::chrono::milliseconds(250);
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
