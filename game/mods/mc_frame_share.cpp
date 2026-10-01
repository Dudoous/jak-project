/*!
 * @file mc_frame_share.cpp
 * Copy each finished Jak frame into a shared memory-mapped file for Minecraft.
 *
 * Triple-buffered: every frame goes into the slot after the last one written, and the global
 * header then points at it. Minecraft always reads the newest complete slot, which Jak doesn't
 * touch again for two more frames, so Minecraft never has to skip a frame because Jak was busy
 * writing, and never gets half of one frame and half of the next.
 */

#include "mc_frame_share.h"

// clang-format off
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <Windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif
// clang-format on

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>

#include "mc_passthrough.h"

#include "common/log/log.h"

#include "third-party/glad/include/glad/glad.h"

namespace mc_passthrough {
namespace {

constexpr size_t kSlotStride = kFrameHeaderSize + (size_t)kFrameMaxWidth * kFrameMaxHeight * 4;
constexpr size_t kFileSize = kFrameHeaderSize + kFrameSlots * kSlotStride;

struct GlobalHeader {
  u32 magic;
  u32 version;
  u32 latest;  // (frame number << 2) | slot of the newest complete frame
  u32 slot_count;
  u32 slot_stride;
  u32 max_width;
  u32 max_height;
  u32 frames_written;
  u32 reserved[8];
};
static_assert(sizeof(GlobalHeader) == kFrameHeaderSize, "frame header must be 64 bytes");

struct SlotHeader {
  u32 seq;  // odd while this slot is being written
  u32 width;
  u32 height;
  u32 format;  // kFrameFormatBgra8
  u32 frame;   // frame number
  // the camera this frame was rendered from (see goal_pose_used), so Minecraft can match it
  u32 pose_valid;
  u32 pose_seq;
  float pose[6];  // x, y, z (Jak meters, eye), yaw, pitch, vfov (Minecraft degrees)
  u32 reserved[3];
};
static_assert(sizeof(SlotHeader) == kFrameHeaderSize, "slot header must be 64 bytes");

u8* g_mapped = nullptr;
bool g_open_failed = false;
std::chrono::steady_clock::time_point g_last_open_attempt{};
u32 g_frame = 0;
u32 g_slot = 0;

// downscale target when Jak's window is bigger than the shared buffer
GLuint g_fbo = 0;
GLuint g_fbo_tex = 0;
int g_fbo_w = 0;
int g_fbo_h = 0;

#ifdef _WIN32
HANDLE g_file = INVALID_HANDLE_VALUE;
HANDLE g_mapping = nullptr;
#endif

std::string frame_file_path() {
  if (const char* env = std::getenv("JAKCRAFT_FRAME_FILE")) {
    return env;
  }
#ifdef _WIN32
  char tmp[MAX_PATH + 1] = {0};
  DWORD n = GetTempPathA(MAX_PATH, tmp);
  std::string dir = (n > 0 && n <= MAX_PATH) ? std::string(tmp) : std::string(".\\");
  if (!dir.empty() && dir.back() != '\\' && dir.back() != '/') {
    dir += '\\';
  }
  return dir + "jakcraft-frames.bin";
#else
  const char* tmpdir = std::getenv("TMPDIR");
  std::string dir = tmpdir ? tmpdir : "/tmp";
  return dir + "/jakcraft-frames.bin";
#endif
}

bool open_mapping() {
  if (g_mapped) {
    return true;
  }
  // don't hammer the filesystem every frame if something is wrong
  auto now = std::chrono::steady_clock::now();
  if (g_open_failed && now - g_last_open_attempt < std::chrono::seconds(5)) {
    return false;
  }
  g_last_open_attempt = now;
  const std::string path = frame_file_path();

#ifdef _WIN32
  // "temporary": Windows keeps the data in memory instead of constantly writing ~1 GB/s of
  // frames to the disk behind the mapping.
  g_file = CreateFileA(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_ALWAYS,
                       FILE_ATTRIBUTE_TEMPORARY, nullptr);
  if (g_file == INVALID_HANDLE_VALUE) {
    lg::error("[mc-passthrough] can't open frame file {}", path);
    g_open_failed = true;
    return false;
  }
  SetFileAttributesA(path.c_str(), FILE_ATTRIBUTE_TEMPORARY);  // if it already existed
  // creating a mapping larger than the file grows the file to that size
  g_mapping = CreateFileMappingA(g_file, nullptr, PAGE_READWRITE, (DWORD)((u64)kFileSize >> 32),
                                 (DWORD)(kFileSize & 0xffffffff), nullptr);
  if (!g_mapping) {
    lg::error("[mc-passthrough] can't map frame file {}", path);
    CloseHandle(g_file);
    g_file = INVALID_HANDLE_VALUE;
    g_open_failed = true;
    return false;
  }
  g_mapped = (u8*)MapViewOfFile(g_mapping, FILE_MAP_WRITE, 0, 0, kFileSize);
  if (!g_mapped) {
    lg::error("[mc-passthrough] can't map view of frame file {}", path);
    CloseHandle(g_mapping);
    CloseHandle(g_file);
    g_mapping = nullptr;
    g_file = INVALID_HANDLE_VALUE;
    g_open_failed = true;
    return false;
  }
#else
  int fd = open(path.c_str(), O_RDWR | O_CREAT, 0644);
  if (fd < 0) {
    lg::error("[mc-passthrough] can't open frame file {}", path);
    g_open_failed = true;
    return false;
  }
  if (ftruncate(fd, (off_t)kFileSize) != 0) {
    lg::error("[mc-passthrough] can't size frame file {}", path);
    close(fd);
    g_open_failed = true;
    return false;
  }
  void* mem = mmap(nullptr, kFileSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  close(fd);  // the mapping keeps the file alive
  if (mem == MAP_FAILED) {
    lg::error("[mc-passthrough] can't mmap frame file {}", path);
    g_open_failed = true;
    return false;
  }
  g_mapped = (u8*)mem;
#endif

  auto* hdr = (GlobalHeader*)g_mapped;
  hdr->magic = kFrameMagic;
  hdr->version = kFrameVersion;
  hdr->slot_count = kFrameSlots;
  hdr->slot_stride = (u32)kSlotStride;
  hdr->max_width = kFrameMaxWidth;
  hdr->max_height = kFrameMaxHeight;
  for (u32 i = 0; i < kFrameSlots; i++) {
    auto* slot = (SlotHeader*)(g_mapped + kFrameHeaderSize + i * kSlotStride);
    if (slot->seq & 1) {
      slot->seq++;  // a previous run died mid-frame
    }
  }
  g_frame = hdr->latest >> 2;
  g_slot = hdr->latest & 3;
  g_open_failed = false;
  lg::info("[mc-passthrough] sharing frames with Minecraft through {}", path);
  return true;
}

/// Shrink (x, y, w, h) of the window's back buffer into our FBO. Returns false if that failed.
bool downscale(int x, int y, int w, int h, int dw, int dh) {
  if (!g_fbo || dw != g_fbo_w || dh != g_fbo_h) {
    if (!g_fbo) {
      glGenFramebuffers(1, &g_fbo);
      glGenTextures(1, &g_fbo_tex);
    }
    GLint old_tex = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_tex);
    glBindTexture(GL_TEXTURE_2D, g_fbo_tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, dw, dh, 0, GL_BGRA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, (GLuint)old_tex);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_fbo);
    glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_fbo_tex, 0);
    if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
      return false;
    }
    g_fbo_w = dw;
    g_fbo_h = dh;
  }
  glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
  glReadBuffer(GL_BACK);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_fbo);
  glBlitFramebuffer(x, y, x + w, y + h, 0, 0, dw, dh, GL_COLOR_BUFFER_BIT, GL_LINEAR);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, g_fbo);
  glReadBuffer(GL_COLOR_ATTACHMENT0);
  return true;
}

}  // namespace

void export_frame_if_wanted(int x, int y, int w, int h) {
  if (w <= 0 || h <= 0 || !steve_mode_active()) {
    return;
  }
  if (!open_mapping()) {
    return;
  }

  GLint old_read = 0, old_draw = 0;
  glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_read);
  glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw);

  // bigger than the shared buffer: shrink it, keeping its shape
  int dw = w, dh = h;
  bool scaled = false;
  if (w > (int)kFrameMaxWidth || h > (int)kFrameMaxHeight) {
    float s = std::min((float)kFrameMaxWidth / (float)w, (float)kFrameMaxHeight / (float)h);
    dw = std::max(1, std::min((int)kFrameMaxWidth, (int)((float)w * s)));
    dh = std::max(1, std::min((int)kFrameMaxHeight, (int)((float)h * s)));
    scaled = downscale(x, y, w, h, dw, dh);
    if (!scaled) {
      // no FBO: share the centre of the image instead
      x += (w - dw) / 2;
      y += (h - dh) / 2;
    }
  }
  if (!scaled) {
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glReadBuffer(GL_BACK);
  }

  auto* hdr = (GlobalHeader*)g_mapped;
  const u32 slot_idx = (g_slot + 1) % kFrameSlots;
  auto* slot = (SlotHeader*)(g_mapped + kFrameHeaderSize + slot_idx * kSlotStride);
  u8* pixels = (u8*)slot + kFrameHeaderSize;

  // seqlock: odd while writing
  const u32 seq = slot->seq;
  slot->seq = seq + 1;
  std::atomic_thread_fence(std::memory_order_seq_cst);
  const u32 frame = g_frame + 1;
  slot->width = (u32)dw;
  slot->height = (u32)dh;
  slot->format = kFrameFormatBgra8;
  slot->frame = frame;
  const FramePose pose = rendered_pose();
  slot->pose_valid = pose.valid;
  slot->pose_seq = pose.seq;
  slot->pose[0] = pose.x;
  slot->pose[1] = pose.y;
  slot->pose[2] = pose.z;
  slot->pose[3] = pose.yaw;
  slot->pose[4] = pose.pitch;
  slot->pose[5] = pose.vfov;

  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glPixelStorei(GL_PACK_ROW_LENGTH, 0);
  glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
  glPixelStorei(GL_PACK_SKIP_ROWS, 0);
  // BGRA is the format GPUs and drivers copy without converting, on both ends
  if (scaled) {
    glReadPixels(0, 0, dw, dh, GL_BGRA, GL_UNSIGNED_BYTE, pixels);
  } else {
    glReadPixels(x, y, dw, dh, GL_BGRA, GL_UNSIGNED_BYTE, pixels);
  }

  std::atomic_thread_fence(std::memory_order_seq_cst);
  slot->seq = seq + 2;
  std::atomic_thread_fence(std::memory_order_seq_cst);
  hdr->latest = (frame << 2) | slot_idx;
  hdr->frames_written = frame;
  g_frame = frame;
  g_slot = slot_idx;

  glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_read);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)old_draw);
  if (old_read == 0) {
    glReadBuffer(GL_BACK);
  }
}

}  // namespace mc_passthrough
