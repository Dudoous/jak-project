/*!
 * @file mc_frame_share.cpp
 * STEVE mode: copy each finished Jak frame into a shared memory-mapped file for Minecraft.
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

constexpr size_t kFileSize =
    kFrameHeaderSize + (size_t)kFrameMaxWidth * (size_t)kFrameMaxHeight * 4;

struct FrameHeader {
  u32 magic;
  u32 version;
  u32 seq;
  u32 width;
  u32 height;
  u32 format;
  u32 frames_written;
  u32 reserved[9];
};
static_assert(sizeof(FrameHeader) == kFrameHeaderSize, "frame header must be 64 bytes");

u8* g_mapped = nullptr;
bool g_open_failed = false;
std::chrono::steady_clock::time_point g_last_open_attempt{};

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
  return dir + "jakcraft-frame.bin";
#else
  const char* tmpdir = std::getenv("TMPDIR");
  std::string dir = tmpdir ? tmpdir : "/tmp";
  return dir + "/jakcraft-frame.bin";
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
  g_file = CreateFileA(path.c_str(), GENERIC_READ | GENERIC_WRITE,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_ALWAYS,
                       FILE_ATTRIBUTE_NORMAL, nullptr);
  if (g_file == INVALID_HANDLE_VALUE) {
    lg::error("[mc-passthrough] can't open frame file {}", path);
    g_open_failed = true;
    return false;
  }
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

  auto* hdr = (FrameHeader*)g_mapped;
  hdr->magic = kFrameMagic;
  hdr->version = kFrameVersion;
  hdr->format = 1;
  if (hdr->seq & 1) {
    hdr->seq++;  // a previous run died mid-frame
  }
  g_open_failed = false;
  lg::info("[mc-passthrough] sharing frames with Minecraft through {}", path);
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
  // larger than the shared buffer: share the centre of the image
  if (w > (int)kFrameMaxWidth) {
    x += (w - (int)kFrameMaxWidth) / 2;
    w = (int)kFrameMaxWidth;
  }
  if (h > (int)kFrameMaxHeight) {
    y += (h - (int)kFrameMaxHeight) / 2;
    h = (int)kFrameMaxHeight;
  }

  auto* hdr = (FrameHeader*)g_mapped;
  u8* pixels = g_mapped + kFrameHeaderSize;

  // seqlock: odd while writing
  u32 seq = hdr->seq;
  hdr->seq = seq + 1;
  std::atomic_thread_fence(std::memory_order_seq_cst);
  hdr->width = (u32)w;
  hdr->height = (u32)h;

  glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
  glReadBuffer(GL_BACK);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glPixelStorei(GL_PACK_ROW_LENGTH, 0);
  glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
  glPixelStorei(GL_PACK_SKIP_ROWS, 0);
  glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

  std::atomic_thread_fence(std::memory_order_seq_cst);
  hdr->frames_written++;
  hdr->seq = seq + 2;
}

}  // namespace mc_passthrough
