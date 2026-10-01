/*!
 * @file mc_overlay.cpp
 * JAK mode: draw Minecraft's frame (Steve's hand, blocks, mobs, HUD) over Jak's frame, and collect
 * mouse/keyboard input from Jak's window for Minecraft.
 */

#include "mc_overlay.h"

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
#include <string>

#include "mc_passthrough.h"

#include "common/log/log.h"

#include "third-party/SDL/include/SDL3/SDL.h"
#include "third-party/glad/include/glad/glad.h"

namespace mc_passthrough {
namespace {

using clock_type = std::chrono::steady_clock;

//////////////////////
// input from Jak's window
//////////////////////

std::atomic<u32> g_buttons{0};
std::atomic<s32> g_wheel{0};
std::atomic<u32> g_hotbar{0};

//////////////////////
// Minecraft frame file
//////////////////////

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
static_assert(sizeof(FrameHeader) == 64, "frame header must be 64 bytes");

const u8* g_mapped = nullptr;
size_t g_mapped_size = 0;
clock_type::time_point g_last_open_attempt{};
u32 g_last_seq = 0xffffffff;
clock_type::time_point g_last_new_frame{};
bool g_have_frame = false;

#ifdef _WIN32
HANDLE g_file = INVALID_HANDLE_VALUE;
HANDLE g_mapping = nullptr;
#endif

std::string mc_frame_path() {
  if (const char* env = std::getenv("JAKCRAFT_MC_FRAME_FILE")) {
    return env;
  }
#ifdef _WIN32
  char tmp[MAX_PATH + 1] = {0};
  DWORD n = GetTempPathA(MAX_PATH, tmp);
  std::string dir = (n > 0 && n <= MAX_PATH) ? std::string(tmp) : std::string(".\\");
  if (!dir.empty() && dir.back() != '\\' && dir.back() != '/') {
    dir += '\\';
  }
  return dir + "jakcraft-mc-frame.bin";
#else
  const char* tmpdir = std::getenv("TMPDIR");
  std::string dir = tmpdir ? tmpdir : "/tmp";
  return dir + "/jakcraft-mc-frame.bin";
#endif
}

/// Minecraft creates the file; we map it read-only once it exists.
bool open_mc_frame() {
  if (g_mapped) {
    return true;
  }
  auto now = clock_type::now();
  if (now - g_last_open_attempt < std::chrono::seconds(2)) {
    return false;
  }
  g_last_open_attempt = now;
  const std::string path = mc_frame_path();
#ifdef _WIN32
  g_file = CreateFileA(path.c_str(), GENERIC_READ,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                       OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (g_file == INVALID_HANDLE_VALUE) {
    return false;
  }
  LARGE_INTEGER size;
  if (!GetFileSizeEx(g_file, &size) || size.QuadPart < 64) {
    CloseHandle(g_file);
    g_file = INVALID_HANDLE_VALUE;
    return false;
  }
  g_mapping = CreateFileMappingA(g_file, nullptr, PAGE_READONLY, 0, 0, nullptr);
  if (!g_mapping) {
    CloseHandle(g_file);
    g_file = INVALID_HANDLE_VALUE;
    return false;
  }
  g_mapped = (const u8*)MapViewOfFile(g_mapping, FILE_MAP_READ, 0, 0, 0);
  if (!g_mapped) {
    CloseHandle(g_mapping);
    CloseHandle(g_file);
    g_mapping = nullptr;
    g_file = INVALID_HANDLE_VALUE;
    return false;
  }
  g_mapped_size = (size_t)size.QuadPart;
#else
  int fd = open(path.c_str(), O_RDONLY);
  if (fd < 0) {
    return false;
  }
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size < 64) {
    close(fd);
    return false;
  }
  void* mem = mmap(nullptr, (size_t)st.st_size, PROT_READ, MAP_SHARED, fd, 0);
  close(fd);
  if (mem == MAP_FAILED) {
    return false;
  }
  g_mapped = (const u8*)mem;
  g_mapped_size = (size_t)st.st_size;
#endif
  lg::info("[mc-passthrough] reading Minecraft frames from {}", path);
  return true;
}

//////////////////////
// GL
//////////////////////

GLuint g_program = 0;
GLuint g_vao = 0;
GLuint g_texture = 0;
GLint g_rect_loc = -1;
GLint g_tex_loc = -1;
int g_tex_w = 0;
int g_tex_h = 0;
bool g_gl_failed = false;

const char* kVertexShader = R"(#version 410 core
uniform vec4 rect;  // x0, y0, x1, y1 in clip space
out vec2 uv;
void main() {
  vec2 p = vec2((gl_VertexID == 1 || gl_VertexID == 3) ? 1.0 : 0.0, (gl_VertexID >= 2) ? 1.0 : 0.0);
  uv = p;
  gl_Position = vec4(mix(rect.xy, rect.zw, p), 0.0, 1.0);
}
)";

const char* kFragmentShader = R"(#version 410 core
uniform sampler2D tex;
in vec2 uv;
out vec4 color;
void main() {
  color = texture(tex, uv);  // premultiplied alpha from Minecraft
}
)";

GLuint compile(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  GLint ok = 0;
  glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[1024];
    glGetShaderInfoLog(s, sizeof(log), nullptr, log);
    lg::error("[mc-passthrough] overlay shader: {}", log);
    glDeleteShader(s);
    return 0;
  }
  return s;
}

bool init_gl() {
  if (g_program) {
    return true;
  }
  if (g_gl_failed) {
    return false;
  }
  GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader);
  GLuint fs = compile(GL_FRAGMENT_SHADER, kFragmentShader);
  if (!vs || !fs) {
    g_gl_failed = true;
    return false;
  }
  g_program = glCreateProgram();
  glAttachShader(g_program, vs);
  glAttachShader(g_program, fs);
  glLinkProgram(g_program);
  glDeleteShader(vs);
  glDeleteShader(fs);
  GLint ok = 0;
  glGetProgramiv(g_program, GL_LINK_STATUS, &ok);
  if (!ok) {
    lg::error("[mc-passthrough] overlay shader failed to link");
    glDeleteProgram(g_program);
    g_program = 0;
    g_gl_failed = true;
    return false;
  }
  g_rect_loc = glGetUniformLocation(g_program, "rect");
  g_tex_loc = glGetUniformLocation(g_program, "tex");
  glGenVertexArrays(1, &g_vao);
  glGenTextures(1, &g_texture);
  glBindTexture(GL_TEXTURE_2D, g_texture);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glBindTexture(GL_TEXTURE_2D, 0);
  return true;
}

/// Upload the newest complete Minecraft frame, if there is one.
void upload_new_frame() {
  const auto* hdr = (const volatile FrameHeader*)g_mapped;
  if (hdr->magic != kMcFrameMagic || hdr->version != 1) {
    return;
  }
  u32 seq = hdr->seq;
  std::atomic_thread_fence(std::memory_order_seq_cst);
  if ((seq & 1) || seq == g_last_seq) {
    return;
  }
  int w = (int)hdr->width;
  int h = (int)hdr->height;
  if (w <= 0 || h <= 0 || 64 + (size_t)w * (size_t)h * 4 > g_mapped_size) {
    return;
  }
  glBindTexture(GL_TEXTURE_2D, g_texture);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
  glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
  if (w != g_tex_w || h != g_tex_h) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    g_tex_w = w;
    g_tex_h = h;
  }
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, g_mapped + 64);
  // a frame Minecraft overwrote mid-upload just gets replaced next frame
  g_last_seq = seq;
  g_last_new_frame = clock_type::now();
  g_have_frame = true;
}

}  // namespace

void on_sdl_event(const SDL_Event& evt) {
  switch (evt.type) {
    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP: {
      u32 bit = 0;
      if (evt.button.button == SDL_BUTTON_LEFT) {
        bit = kHostButtonAttack;
      } else if (evt.button.button == SDL_BUTTON_RIGHT) {
        bit = kHostButtonUse;
      } else if (evt.button.button == SDL_BUTTON_MIDDLE) {
        bit = kHostButtonPick;
      }
      if (evt.type == SDL_EVENT_MOUSE_BUTTON_DOWN) {
        g_buttons.fetch_or(bit);
      } else {
        g_buttons.fetch_and(~bit);
      }
      break;
    }
    case SDL_EVENT_MOUSE_WHEEL:
      if (evt.wheel.y > 0) {
        g_wheel.fetch_add(1);
      } else if (evt.wheel.y < 0) {
        g_wheel.fetch_sub(1);
      }
      break;
    case SDL_EVENT_KEY_DOWN:
      if (!evt.key.repeat && evt.key.key >= SDLK_1 && evt.key.key <= SDLK_9) {
        g_hotbar.store((u32)(evt.key.key - SDLK_1 + 1));
      }
      break;
    case SDL_EVENT_WINDOW_FOCUS_LOST:
      g_buttons.store(0);  // never leave a button "held" when alt-tabbing
      break;
    default:
      break;
  }
}

u32 host_buttons() {
  return g_buttons.load();
}

s32 take_host_wheel() {
  return g_wheel.exchange(0);
}

u32 take_host_hotbar() {
  return g_hotbar.exchange(0);
}

void draw_mc_overlay_if_wanted(int x, int y, int w, int h) {
  if (w <= 0 || h <= 0 || !jak_mode_active()) {
    return;
  }
  if (!open_mc_frame() || !init_gl()) {
    return;
  }
  upload_new_frame();
  if (!g_have_frame || clock_type::now() - g_last_new_frame > std::chrono::milliseconds(500)) {
    return;  // Minecraft stopped sending frames
  }

  // save the state we touch
  GLint old_program, old_vao, old_tex, old_active_tex, old_draw_fb, old_viewport[4];
  GLint old_src_rgb, old_dst_rgb, old_src_a, old_dst_a;
  glGetIntegerv(GL_CURRENT_PROGRAM, &old_program);
  glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &old_vao);
  glGetIntegerv(GL_ACTIVE_TEXTURE, &old_active_tex);
  glActiveTexture(GL_TEXTURE0);
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_tex);
  glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &old_draw_fb);
  glGetIntegerv(GL_VIEWPORT, old_viewport);
  glGetIntegerv(GL_BLEND_SRC_RGB, &old_src_rgb);
  glGetIntegerv(GL_BLEND_DST_RGB, &old_dst_rgb);
  glGetIntegerv(GL_BLEND_SRC_ALPHA, &old_src_a);
  glGetIntegerv(GL_BLEND_DST_ALPHA, &old_dst_a);
  GLboolean old_blend = glIsEnabled(GL_BLEND);
  GLboolean old_depth = glIsEnabled(GL_DEPTH_TEST);
  GLboolean old_scissor = glIsEnabled(GL_SCISSOR_TEST);
  GLboolean old_cull = glIsEnabled(GL_CULL_FACE);

  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
  glViewport(x, y, w, h);
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_SCISSOR_TEST);
  glDisable(GL_CULL_FACE);
  glEnable(GL_BLEND);
  glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
  glUseProgram(g_program);
  glBindVertexArray(g_vao);
  glBindTexture(GL_TEXTURE_2D, g_texture);
  glUniform1i(g_tex_loc, 0);
  glUniform4f(g_rect_loc, -1.f, -1.f, 1.f, 1.f);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

  // restore
  glBindTexture(GL_TEXTURE_2D, (GLuint)old_tex);
  glActiveTexture((GLenum)old_active_tex);
  glBindVertexArray((GLuint)old_vao);
  glUseProgram((GLuint)old_program);
  glBlendFuncSeparate((GLenum)old_src_rgb, (GLenum)old_dst_rgb, (GLenum)old_src_a,
                      (GLenum)old_dst_a);
  if (!old_blend) {
    glDisable(GL_BLEND);
  }
  if (old_depth) {
    glEnable(GL_DEPTH_TEST);
  }
  if (old_scissor) {
    glEnable(GL_SCISSOR_TEST);
  }
  if (old_cull) {
    glEnable(GL_CULL_FACE);
  }
  glViewport(old_viewport[0], old_viewport[1], old_viewport[2], old_viewport[3]);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)old_draw_fb);
}

}  // namespace mc_passthrough
