// rexg-render: the drawing half of the ghostty-rex client (T207).
//
// The bb attach client (desktop/build/ghostty-rex/src/rexg) owns Rex, keys
// and every decision; this program only draws. It keeps one libghostty-vt
// terminal per Rex block, fed with the block's data stream (a Ghostty
// snapshot, then plain VT), and composes the visible panes and the bb
// client's chrome (tab bar, titles, status line, which-key, minibuffer,
// hints) into the terminal it runs in, Ghostty, as Herdr does. Nothing of
// the panes' own bytes reaches Ghostty, so Ghostty never answers a pane's
// terminal queries twice: Rex's terminal already does.
//
// stdin: frames from bb, each a type byte, a 4-byte big-endian length and a
// payload (see handle()). stdout: the terminal. stderr: lines back to bb
// (size COLS ROWS, ready ID BYTES MS when a pane's screen is in, done ID
// BYTES MS when its whole snapshot is, mode ID BITS, text replies, pane errors). Build: make build NAME=ghostty-rex, or
// cc -O2 rexg-render.c $(pkg-config --cflags --libs libghostty-vt) -lpthread
#include <ghostty/vt.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define MAXP 64
#define KEEP 16  // closed panes kept for an instant redraw
#define NCP 6

typedef struct {
  uint32_t cp[NCP];
  uint8_t n;      // codepoints; 0 is a blank
  uint8_t wide;   // 0 narrow, 1 wide head, 2 spacer
  uint8_t flags;  // 1 bold 2 italic 4 underline 8 inverse 16 faint 32 strike
  uint8_t fgk, bgk;  // 0 default, 1 palette (in r), 2 rgb
  uint8_t fr, fg, fb, br, bg, bb;
} Cell;

typedef struct {
  char id[128];
  int used, ready, failed;
  // open: a stream feeds it. A closed pane keeps its terminal, so showing
  // it again paints its last screen at once while Rex sends a new snapshot
  // (which for pi, with inline images, can be tens of MB); closed_at picks
  // the oldest to free past KEEP.
  int open; long closed_at, opened_at;
  pthread_t th;
  pthread_mutex_t mu;
  // The stream's bytes, queued by the main loop for the pane's thread: a
  // pipe made the main loop wait while a large snapshot decoded, and
  // nothing redrew meanwhile.
  pthread_mutex_t qmu; pthread_cond_t qcv;
  uint8_t *q; size_t qoff, qn, qcap; int qclosed;
  volatile size_t got;  // bytes of the current stream so far
  // The pane's own cells, of size pcw by pch, refreshed row by row as
  // libghostty marks rows dirty; pc_stale forces a full read. A frame that
  // finds mu held draws these too: the screen's front buffer, which a resize
  // or a full redraw blanks, made the pane flash empty under its cursor (T223).
  Cell *gc; int pcw, pch, pc_stale;
  // Sizes from Rex's stream, each at the stream position it comes at (the
  // bytes before it), applied by the pane's thread in order with the bytes,
  // under qmu. Applied as they came, a program's redraw for a new size could
  // land in the old one, or old bytes in the new one: Emacs's mode line
  // stayed on a wrong row (T225).
  struct { size_t at; int c, r; } sz[16]; int nsz;
  size_t taken;  // bytes of the current stream its thread has taken
  GhosttyTerminal term;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  int x, y, w, h, shown;
  int mouse;  // the program asked for mouse reports (last told to bb)
  // The cursor as the cells last drawn have it, read under mu with them: a
  // separate read could miss the lock and drop the cursor with nothing left
  // to redraw it (T223). Screen cells; cur_ok 0 when hidden or out of view.
  int cur_ok, cur_x, cur_y, cur_style;  // cur_x, cur_y in the pane
  // The terminal is from the current stream's snapshot. A pane shown again
  // keeps its last screen until then, but not its last cursor, which can be
  // stale: the program may have changed mode meanwhile (T223).
  int fresh;
} Pane;

static Pane panes[MAXP];
// The shown panes in bb's layout order, bottom to top: a floating pane
// comes after the tiled ones it covers. Drawing in slot order put an older
// float over a newer one (T223).
static Pane *order[MAXP]; static int norder;
static long now_ms(void);
static int W = 80, H = 24;
static Cell *front, *back;
static int wake[2], winch[2];
static FILE *ctl;
static pthread_mutex_t ctl_mu = PTHREAD_MUTEX_INITIALIZER;
static volatile int dirty = 1, full = 1;
// bb has sent part of a view (layout, overlay, cursor) and not yet its end
// ('k'): no frame until it has, so none draws half of one (T223).
static int view_open;
// Synchronized output (CSI ?2026) around each frame; REXG_SYNC=0 drops it, so
// Bombadil 0.8, which samples at each finished synchronized frame, waits for a
// quiet gap instead and judges the screen once bb has handled the key.
static int sync_out = 1;
static struct termios saved;
static int have_saved;
// cursor: 0 hidden, 1 at focus pane, 2 at cx,cy
static int cur_mode = 0, cx, cy;
static char cur_pane[128];

typedef struct { int row, col; Cell c; } Span;
static Span *spans; static size_t nspans, capspans;

static void say(const char *fmt, ...) {
  pthread_mutex_lock(&ctl_mu);
  va_list ap; va_start(ap, fmt); vfprintf(ctl, fmt, ap); va_end(ap);
  fflush(ctl);
  pthread_mutex_unlock(&ctl_mu);
}

static void poke(void) { char b = 1; ssize_t r = write(wake[1], &b, 1); (void)r; }

// ---- output buffer ----
static char *ob; static size_t on, ocap;
// REXG_TRACE=FILE: a copy of everything written to the terminal, to see the
// exact frames (cursor escapes included) a bug report's screen came from.
static int trace_fd = -1;
static void oput(const char *s, size_t n) {
  if (on + n > ocap) { ocap = (on + n) * 2 + 65536; ob = realloc(ob, ocap); }
  memcpy(ob + on, s, n); on += n;
}
static void ostr(const char *s) { oput(s, strlen(s)); }
static void ofmt(const char *fmt, ...) {
  char b[128]; va_list ap; va_start(ap, fmt); int n = vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
  oput(b, (size_t)n);
}
static void oflush(void) {
  size_t off = 0;
  while (off < on) { ssize_t n = write(1, ob + off, on - off); if (n <= 0) { if (errno == EINTR || errno == EAGAIN) continue; break; } off += (size_t)n; }
  if (trace_fd >= 0) { ssize_t r = write(trace_fd, ob, on); (void)r; }
  on = 0;
}
static void outf8(uint32_t c) {
  char b[4]; int n;
  if (c < 0x80) { b[0] = (char)c; n = 1; }
  else if (c < 0x800) { b[0] = (char)(0xC0 | (c >> 6)); b[1] = (char)(0x80 | (c & 0x3F)); n = 2; }
  else if (c < 0x10000) { b[0] = (char)(0xE0 | (c >> 12)); b[1] = (char)(0x80 | ((c >> 6) & 0x3F)); b[2] = (char)(0x80 | (c & 0x3F)); n = 3; }
  else { b[0] = (char)(0xF0 | (c >> 18)); b[1] = (char)(0x80 | ((c >> 12) & 0x3F)); b[2] = (char)(0x80 | ((c >> 6) & 0x3F)); b[3] = (char)(0x80 | (c & 0x3F)); n = 4; }
  oput(b, (size_t)n);
}

// ---- panes ----
static Pane *find(const char *id) {
  for (int i = 0; i < MAXP; i++) if (panes[i].used && !strcmp(panes[i].id, id)) return &panes[i];
  return NULL;
}

// The snapshot decoder asks for a byte or a few at a time: a 51 MB snapshot
// (pi's inline images) took 46 million read(2) calls through a pipe and 10 s
// before the pane showed (T210). So the queue is taken in large chunks into
// FED, and the decoder is served from it; GIVEN is how much it has had. FED
// keeps every byte taken, so the VT after the snapshot (from its data-source
// offset to LEN) is still there when the decoder is done.
static void qput(Pane *p, const uint8_t *b, size_t n) {
  pthread_mutex_lock(&p->qmu);
  if (p->qoff == p->qn) p->qoff = p->qn = 0;
  if (p->qn + n > p->qcap) {
    if (p->qoff) { memmove(p->q, p->q + p->qoff, p->qn - p->qoff); p->qn -= p->qoff; p->qoff = 0; }
    if (p->qn + n > p->qcap) { p->qcap = (p->qn + n) * 2; p->q = realloc(p->q, p->qcap); }
  }
  memcpy(p->q + p->qn, b, n); p->qn += n; p->got += n;
  pthread_cond_signal(&p->qcv);
  pthread_mutex_unlock(&p->qmu);
}

// Up to CAP queued bytes into BUF, waiting for some; 0 once the stream closed.
static size_t qtake(Pane *p, uint8_t *buf, size_t cap) {
  pthread_mutex_lock(&p->qmu);
  while (p->qoff == p->qn && !p->qclosed) pthread_cond_wait(&p->qcv, &p->qmu);
  size_t k = p->qn - p->qoff < cap ? p->qn - p->qoff : cap;
  memcpy(buf, p->q + p->qoff, k); p->qoff += k; p->taken += k;
  pthread_mutex_unlock(&p->qmu);
  return k;
}

// Under qmu: how many queued bytes come before the next size, and whether
// that size is due (none do).
static size_t qlimit(Pane *p) {
  size_t k = p->qn - p->qoff;
  if (p->nsz) { size_t gap = p->sz[0].at > p->taken ? p->sz[0].at - p->taken : 0; if (gap < k) k = gap; }
  return k;
}
static int qdue(Pane *p) { return p->nsz && p->sz[0].at <= p->taken; }

// Under qmu: take the due size into *C, *R.
static void qpop(Pane *p, int *c, int *r) {
  *c = p->sz[0].c; *r = p->sz[0].r;
  memmove(&p->sz[0], &p->sz[1], (size_t)(p->nsz - 1) * sizeof p->sz[0]); p->nsz--;
}

// Up to CAP bytes before the next size, waiting up to MS for some (MS < 0:
// as long as it takes). Returns 0 with *IDLE set after MS with nothing; 0 without it when a size is due or
// the stream closed.
static size_t qtake_idle(Pane *p, uint8_t *buf, size_t cap, int ms, int *idle) {
  pthread_mutex_lock(&p->qmu);
  if (!qlimit(p) && !qdue(p) && !p->qclosed && ms < 0)
    while (!qlimit(p) && !qdue(p) && !p->qclosed) pthread_cond_wait(&p->qcv, &p->qmu);
  if (!qlimit(p) && !qdue(p) && !p->qclosed) {
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += ms / 1000; ts.tv_nsec += (long)(ms % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
    while (!qlimit(p) && !qdue(p) && !p->qclosed)
      if (pthread_cond_timedwait(&p->qcv, &p->qmu, &ts) == ETIMEDOUT) { *idle = 1; pthread_mutex_unlock(&p->qmu); return 0; }
  }
  size_t k = qlimit(p) < cap ? qlimit(p) : cap;
  memcpy(buf, p->q + p->qoff, k); p->qoff += k; p->taken += k;
  pthread_mutex_unlock(&p->qmu);
  return k;
}

// The pane's thread applies the next size if it comes at or before stream
// position AT; 1 if it did.
static int apply_due(Pane *p, size_t at) {
  int c, r, due;
  pthread_mutex_lock(&p->qmu);
  if ((due = p->nsz && p->sz[0].at <= at)) qpop(p, &c, &r);
  pthread_mutex_unlock(&p->qmu);
  if (!due) return 0;
  pthread_mutex_lock(&p->mu);
  if (p->term) ghostty_terminal_resize(p->term, (uint16_t)c, (uint16_t)r, 0, 0);
  pthread_mutex_unlock(&p->mu);
  dirty = 1; poke();
  return 1;
}
static int qready(Pane *p) { pthread_mutex_lock(&p->qmu); int r = p->qoff != p->qn || p->qclosed || qdue(p); pthread_mutex_unlock(&p->qmu); return r; }

typedef struct { Pane *p; uint8_t *fed; size_t len, cap, given; } Rd;
static bool rd(void *u, uint8_t *buf, size_t cap, size_t *out) {
  Rd *r = u;
  if (r->given == r->len) {
    if (r->cap - r->len < (1 << 20)) { r->cap = r->cap * 2 + (1 << 20); r->fed = realloc(r->fed, r->cap); }
    r->len += qtake(r->p, r->fed + r->len, r->cap - r->len);
  }
  size_t k = r->len - r->given < cap ? r->len - r->given : cap;
  memcpy(buf, r->fed + r->given, k); r->given += k;
  *out = k;
  return true;
}

// The stream: a Ghostty snapshot, then live VT. The snapshot is decoded in
// pieces: READY gives the visible screen, which shows at once, then each
// next call adds a history page or an image's pixels. A whole decode first
// kept the pane blank until pi's 51 MB of images were in.
static void *pane_thread(void *arg) {
  Pane *p = arg;
  Rd r = { p, NULL, 0, 0, 0 };
  GhosttySnapshotDecoder dec = NULL; GhosttyTerminal term = NULL;
  GhosttyReader reader = { rd, &r };
  // History is restored compressed and no image pixels are kept: this
  // terminal only feeds cells to Ghostty, which never sees a pane's images.
  bool yes = true; uint64_t no_images = 0;
  if (ghostty_snapshot_decoder_new(NULL, &dec, reader) != GHOSTTY_SUCCESS ||
      ghostty_snapshot_decoder_set(dec, GHOSTTY_SNAPSHOT_DECODER_OPT_COMPRESS_HISTORY, &yes) != GHOSTTY_SUCCESS ||
      ghostty_snapshot_decoder_set(dec, GHOSTTY_SNAPSHOT_DECODER_OPT_KITTY_IMAGE_STORAGE_LIMIT, &no_images) != GHOSTTY_SUCCESS ||
      ghostty_snapshot_decoder_ready(dec, &term) != GHOSTTY_SUCCESS) {
    say("err %s snapshot\n", p->id);
    pthread_mutex_lock(&p->mu); p->failed = 1; pthread_mutex_unlock(&p->mu);
    ghostty_snapshot_decoder_free(dec);
    free(r.fed);
    dirty = 1; poke();
    return NULL;
  }
  pthread_mutex_lock(&p->mu);
  // A reopened pane showed its last screen until now.
  if (p->cells) ghostty_render_state_row_cells_free(p->cells);
  if (p->rows) ghostty_render_state_row_iterator_free(p->rows);
  if (p->rs) ghostty_render_state_free(p->rs);
  if (p->term) ghostty_terminal_free(p->term);
  p->term = term;
  ghostty_render_state_new(NULL, &p->rs);
  p->pc_stale = 1;
  ghostty_render_state_row_iterator_new(NULL, &p->rows);
  ghostty_render_state_row_cells_new(NULL, &p->cells);
  p->ready = 1; p->fresh = 1;
  pthread_mutex_unlock(&p->mu);
  // How long the screen took, and later the whole snapshot: REXG_LOG has
  // these lines, which would have shown T210's 10 s decode at first look.
  say("ready %s %zu %ld\n", p->id, (size_t)p->got, now_ms() - p->opened_at);
  dirty = 1; poke();
  // History and images. The decoder may only touch the terminal between
  // calls, so each holds mu; draw_pane does not wait for it.
  GhosttyResult res;
  do {
    pthread_mutex_lock(&p->mu);
    res = ghostty_snapshot_decoder_next(dec);
    pthread_mutex_unlock(&p->mu);
    dirty = 1; poke();
  } while (res == GHOSTTY_SUCCESS);
  size_t off = 0;
  if (res != GHOSTTY_NO_VALUE ||
      ghostty_snapshot_decoder_get(dec, GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, &off) != GHOSTTY_SUCCESS) {
    // The screen so far stays; where the live VT starts is unknown.
    say("err %s snapshot\n", p->id);
    p->failed = 1;
    ghostty_snapshot_decoder_free(dec);
    free(r.fed);
    return NULL;
  }
  // The bytes the decoder read past the snapshot, with the sizes that came
  // among them, in order.
  for (size_t pos = off;;) {
    if (apply_due(p, pos)) continue;
    if (pos >= r.len) break;
    pthread_mutex_lock(&p->qmu);
    size_t lim = p->nsz && p->sz[0].at < r.len ? p->sz[0].at : r.len;
    pthread_mutex_unlock(&p->qmu);
    pthread_mutex_lock(&p->mu);
    ghostty_terminal_vt_write(p->term, r.fed + pos, lim - pos);
    pthread_mutex_unlock(&p->mu);
    pos = lim;
  }
  dirty = 1; poke();  // these bytes, and a frame that found the lock held
  say("done %s %zu %ld\n", p->id, off, now_ms() - p->opened_at);
  ghostty_snapshot_decoder_free(dec);
  free(r.fed);
  uint8_t b[65536];
  size_t n;
  { uint64_t z = 0; pthread_mutex_lock(&p->mu); ghostty_terminal_set(p->term, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &z); pthread_mutex_unlock(&p->mu); }
  uint64_t done_at = (uint64_t)-1;  // activity token when compression last completed
  // Output since compression last completed: only then does a quiet second
  // compress, so a pane that stays idle never wakes.
  int wrote = 1;
  for (;;) {
    if (apply_due(p, p->taken)) continue;
    int idle = 0;
    n = qtake_idle(p, b, 65536, wrote ? 1000 : -1, &idle);
    if (idle) {
      // A second with no output: compress history a bounded step at a time,
      // releasing the lock between steps so frames are not held.
      uint64_t act = 0;
      pthread_mutex_lock(&p->mu); ghostty_terminal_compression_activity(p->term, &act); pthread_mutex_unlock(&p->mu);
      if (act == done_at) { wrote = 0; continue; }
      GhosttyTerminalCompressionResult res = GHOSTTY_TERMINAL_COMPRESSION_RESULT_PENDING;
      while (res == GHOSTTY_TERMINAL_COMPRESSION_RESULT_PENDING && !qready(p)) {
        pthread_mutex_lock(&p->mu);
        ghostty_terminal_compress(p->term, GHOSTTY_TERMINAL_COMPRESSION_MODE_INCREMENTAL, &res);
        if (res != GHOSTTY_TERMINAL_COMPRESSION_RESULT_PENDING) { ghostty_terminal_compression_activity(p->term, &done_at); wrote = 0; }
        pthread_mutex_unlock(&p->mu);
      }
      continue;
    }
    if (n == 0) {
      // A size is due, or the stream closed.
      pthread_mutex_lock(&p->qmu); int closed = p->qclosed && !qdue(p) && p->qoff == p->qn; pthread_mutex_unlock(&p->qmu);
      if (closed) break;
      continue;
    }
    pthread_mutex_lock(&p->mu);
    ghostty_terminal_vt_write(p->term, b, n);
    pthread_mutex_unlock(&p->mu);
    wrote = 1;
    dirty = 1; poke();
  }
  return NULL;
}

static void pane_free(Pane *p) {
  if (p->cells) ghostty_render_state_row_cells_free(p->cells);
  if (p->rows) ghostty_render_state_row_iterator_free(p->rows);
  if (p->rs) ghostty_render_state_free(p->rs);
  if (p->term) ghostty_terminal_free(p->term);
  free(p->q); free(p->gc);
  pthread_mutex_destroy(&p->mu); pthread_mutex_destroy(&p->qmu); pthread_cond_destroy(&p->qcv);
  p->used = 0;
  full = 1;
}

// Stop P's stream; its terminal stays, to show again at once.
static void pane_close(Pane *p) {
  static long tick;
  if (!p->open) return;
  pthread_mutex_lock(&p->qmu); p->qclosed = 1; pthread_cond_signal(&p->qcv); pthread_mutex_unlock(&p->qmu);
  pthread_join(p->th, NULL);
  p->open = 0; p->closed_at = ++tick; p->qoff = p->qn = 0;
  full = 1;
  int kept = 0; Pane *oldest = NULL;
  for (int i = 0; i < MAXP; i++) if (panes[i].used && !panes[i].open) {
    kept++;
    if (!oldest || panes[i].closed_at < oldest->closed_at) oldest = &panes[i];
  }
  if (kept > KEEP) pane_free(oldest);
}

static void pane_open(const char *id) {
  Pane *p = find(id);
  if (p && p->open) return;
  if (!p) {
    for (int i = 0; i < MAXP && !p; i++) if (!panes[i].used) p = &panes[i];
    // Every slot taken: give up the oldest closed pane's.
    for (int i = 0; i < MAXP && (!p || p->used); i++)
      if (panes[i].used && !panes[i].open && (!p || panes[i].closed_at < p->closed_at)) p = &panes[i];
    if (!p) { say("err %s too-many-panes\n", id); return; }
    if (p->used) pane_free(p);
    memset(p, 0, sizeof *p);
    snprintf(p->id, sizeof p->id, "%s", id);
    pthread_mutex_init(&p->mu, NULL); pthread_mutex_init(&p->qmu, NULL); pthread_cond_init(&p->qcv, NULL);
    p->used = 1;
  }
  p->open = 1; p->failed = 0; p->got = 0; p->qclosed = 0; p->qoff = p->qn = 0; p->opened_at = now_ms();
  p->taken = 0; p->nsz = 0;
  // Its thread is not running yet, so mu is free.
  pthread_mutex_lock(&p->mu); p->fresh = 0; p->cur_ok = 0; pthread_mutex_unlock(&p->mu);
  pthread_create(&p->th, NULL, pane_thread, p);
}

static void write_all(int fd, const uint8_t *b, size_t n) {
  while (n) { ssize_t k = write(fd, b, n); if (k < 0) { if (errno == EINTR) continue; return; } b += k; n -= (size_t)k; }
}

// ---- drawing ----
static void blank(Cell *c) { memset(c, 0, sizeof *c); }

static void color_of(GhosttyStyleColor sc, uint8_t *k, uint8_t *r, uint8_t *g, uint8_t *b) {
  switch (sc.tag) {
    case GHOSTTY_STYLE_COLOR_PALETTE: *k = 1; *r = sc.value.palette; break;
    case GHOSTTY_STYLE_COLOR_RGB: *k = 2; *r = sc.value.rgb.r; *g = sc.value.rgb.g; *b = sc.value.rgb.b; break;
    default: *k = 0;
  }
}

static void draw_pane(Pane *p) {
  // The pane's thread holds mu while it decodes a piece of the snapshot:
  // keep what the pane showed, and try again next frame.
  if (pthread_mutex_trylock(&p->mu) != 0) {
    for (int y = 0; p->gc && y < p->h && y < p->pch; y++)
      for (int x = 0; x < p->w && x < p->pcw; x++) {
        int sx = p->x + x, sy = p->y + y;
        if (sx < 0 || sy < 0 || sx >= W || sy >= H) continue;
        Cell c = p->gc[y * p->pcw + x];
        if (c.wide == 1 && (x + 1 >= p->w || sx + 1 >= W)) blank(&c);  // its tail is cut off
        back[sy * W + sx] = c;
      }
    // The cursor was where the program had it in a pane of that size.
    if (p->pcw != p->w || p->pch != p->h) p->cur_ok = 0;
    dirty = 1;
    return;
  }
  if (!p->ready || !p->term) {
    int failed = p->failed;
    p->cur_ok = 0;
    pthread_mutex_unlock(&p->mu);
    // Until the first snapshot is in, say so instead of a blank pane.
    char msg[96];
    if (failed) snprintf(msg, sizeof msg, "this pane's snapshot did not decode");
    else snprintf(msg, sizeof msg, "loading this pane from Rex (%.1f MB)", (double)p->got / 1e6);
    int sy = p->y + p->h / 2, sx = p->x + (p->w - (int)strlen(msg)) / 2;
    if (sx < p->x) sx = p->x;
    for (int i = 0; msg[i] && sx + i < p->x + p->w; i++) {
      if (sy < 0 || sy >= H || sx + i < 0 || sx + i >= W) continue;
      Cell *c = &back[sy * W + sx + i];
      blank(c); c->flags = 16; c->cp[0] = (uint8_t)msg[i]; c->n = msg[i] != ' ';
    }
    return;
  }
  ghostty_render_state_update(p->rs, p->term);
  ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &p->rows);
  // Keep the pane's cells and re-read only the rows libghostty marks dirty;
  // a new size or a new terminal reads them all.
  int all = !p->gc || p->pcw != p->w || p->pch != p->h || p->pc_stale;
  if (all) {
    free(p->gc); p->gc = calloc((size_t)(p->w * p->h), sizeof(Cell)); p->pcw = p->w; p->pch = p->h; p->pc_stale = 0;
  }
  uint16_t ry = 0; int yy = 0;
  while (all ? (ghostty_render_state_row_iterator_next(p->rows) && (ry = (uint16_t)yy++, 1))
             : ghostty_render_state_row_iterator_next_dirty(p->rows, &ry)) {
    if (ry >= p->h) { if (all) break; else continue; }
    ghostty_render_state_row_get(p->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &p->cells);
    Cell *row = &p->gc[ry * p->w];
    for (int i = 0; i < p->w; i++) blank(&row[i]);
    int x = 0;
    while (ghostty_render_state_row_cells_next(p->cells) && x < p->w) {
      Cell *c = &row[x];
      GhosttyCell raw = 0;
      ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
      GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      GhosttyStyle st;
      ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &st);
      color_of(st.fg_color, &c->fgk, &c->fr, &c->fg, &c->fb);
      color_of(st.bg_color, &c->bgk, &c->br, &c->bg, &c->bb);
      if (st.flags & GHOSTTY_STYLE_FLAG_BOLD) c->flags |= 1;
      if (st.flags & GHOSTTY_STYLE_FLAG_ITALIC) c->flags |= 2;
      if (GHOSTTY_STYLE_UNDERLINE(st)) c->flags |= 4;
      if (st.flags & GHOSTTY_STYLE_FLAG_INVERSE) c->flags |= 8;
      if (st.flags & GHOSTTY_STYLE_FLAG_FAINT) c->flags |= 16;
      if (st.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) c->flags |= 32;
      if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) { c->wide = 2; x++; continue; }
      if (wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) { x++; continue; }
      if (wide == GHOSTTY_CELL_WIDE_WIDE) {
        if (x + 1 >= p->w) { x++; continue; }
        c->wide = 1;
      }
      uint32_t len = 0;
      ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &len);
      if (len && !(st.flags & GHOSTTY_STYLE_FLAG_INVISIBLE)) {
        uint32_t cps[16];
        GhosttyCodepointBuffer gb = { .ptr = cps, .cap = 16 };
        ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &gb);
        size_t k = gb.len < NCP ? gb.len : NCP;
        for (size_t i = 0; i < k; i++) c->cp[i] = cps[i];
        c->n = (uint8_t)k;
      }
      x++;
    }
  }
  ghostty_render_state_clean(p->rs);
  for (int y = 0; y < p->h; y++)
    for (int x = 0; x < p->w; x++) {
      int sx = p->x + x, sy = p->y + y;
      if (sx < 0 || sy < 0 || sx >= W || sy >= H) continue;
      Cell c = p->gc[y * p->w + x];
      if (c.wide == 1 && sx + 1 >= W) blank(&c);
      back[sy * W + sx] = c;
    }
  GhosttyRenderStateCursor cu = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  p->cur_ok = p->fresh && ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cu) == GHOSTTY_SUCCESS &&
              cu.viewport_has_value && cu.visible && cu.viewport_x < p->w && cu.viewport_y < p->h;
  if (p->cur_ok) { p->cur_x = cu.viewport_x; p->cur_y = cu.viewport_y; p->cur_style = (int)cu.visual_style; }
  bool mouse = false;
  ghostty_terminal_get(p->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse);
  bool active = true;
  ghostty_terminal_get(p->term, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &active);
  int m = (mouse ? 1 : 0) | (active ? 0 : 2);
  pthread_mutex_unlock(&p->mu);
  // bb routes clicks and the wheel by whether the program wants them, and
  // marks a pane scrolled into its history.
  if (m != p->mouse) { p->mouse = m; say("mode %s %d\n", p->id, m); }
}

static int same(const Cell *a, const Cell *b) { return !memcmp(a, b, sizeof *a); }

static void sgr(const Cell *c) {
  ostr("\x1b[0");
  if (c->flags & 1) ostr(";1");
  if (c->flags & 16) ostr(";2");
  if (c->flags & 2) ostr(";3");
  if (c->flags & 4) ostr(";4");
  if (c->flags & 8) ostr(";7");
  if (c->flags & 32) ostr(";9");
  if (c->fgk == 1) ofmt(";38;5;%u", c->fr); else if (c->fgk == 2) ofmt(";38;2;%u;%u;%u", c->fr, c->fg, c->fb);
  if (c->bgk == 1) ofmt(";48;5;%u", c->br); else if (c->bgk == 2) ofmt(";48;2;%u;%u;%u", c->br, c->bg, c->bb);
  ostr("m");
}
static int same_style(const Cell *a, const Cell *b) {
  return a->flags == b->flags && a->fgk == b->fgk && a->bgk == b->bgk && a->fr == b->fr && a->fg == b->fg && a->fb == b->fb &&
         a->br == b->br && a->bg == b->bg && a->bb == b->bb;
}

static void frame(void) {
  for (int i = 0; i < W * H; i++) blank(&back[i]);
  for (int i = 0; i < norder; i++) if (order[i]->used && order[i]->shown) draw_pane(order[i]);
  for (size_t i = 0; i < nspans; i++) {
    Span *s = &spans[i];
    if (s->row < 0 || s->row >= H || s->col < 0 || s->col >= W) continue;
    Cell *c = &back[s->row * W + s->col];
    // Flag 128 marks a selection: the cell keeps its text, reversed.
    if (s->c.flags & 128) { c->flags ^= 8; continue; }
    // An overlay over half of a wide character blanks the other half.
    if (c->wide == 2 && s->col > 0) blank(&back[s->row * W + s->col - 1]);
    if (c->wide == 1 && s->col + 1 < W) blank(&back[s->row * W + s->col + 1]);
    *c = s->c;
  }
  if (sync_out) ostr("\x1b[?2026h");
  ostr("\x1b[?25l");
  if (full) { ostr("\x1b[0m\x1b[2J"); for (int i = 0; i < W * H; i++) { blank(&front[i]); front[i].n = 255; } }
  Cell style; blank(&style); int have_style = 0;
  int px = -1, py = -1;
  for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
    Cell *b = &back[y * W + x], *f = &front[y * W + x];
    if (b->wide == 2) { *f = *b; continue; }
    int wide_changed = b->wide == 1 && x + 1 < W && !same(&back[y * W + x + 1], &front[y * W + x + 1]);
    if (same(b, f) && !wide_changed) continue;
    if (px != x || py != y) ofmt("\x1b[%d;%dH", y + 1, x + 1);
    if (!have_style || !same_style(&style, b)) { sgr(b); style = *b; have_style = 1; }
    if (b->n == 0) ostr(" "); else for (int i = 0; i < b->n; i++) outf8(b->cp[i]);
    *f = *b;
    px = x + (b->wide == 1 ? 2 : 1); py = y;
    if (b->wide == 1 && x + 1 < W) { front[y * W + x + 1] = back[y * W + x + 1]; x++; }
  }
  ostr("\x1b[0m");
  full = 0;
  int ok = 0, x = 0, y = 0, cs = 1;
  if (cur_mode == 1) {
    Pane *p = find(cur_pane);
    if (p && p->shown && p->cur_ok) { ok = 1; x = p->x + p->cur_x; y = p->y + p->cur_y; cs = p->cur_style; }
  }
  else if (cur_mode == 2) { ok = 1; x = cx; y = cy; cs = 1; }
  if (ok) {
    // libghostty's cursor styles: 0 bar, 1 block, 2 underline, 3 hollow
    // block. A block, the default, is Ghostty's configured cursor (CSI 0 q),
    // so the person's cursor-style holds unless a program asks for a bar or
    // an underline.
    int dec = cs == 0 ? 6 : cs == 2 ? 4 : 0;
    ofmt("\x1b[%d q\x1b[%d;%dH\x1b[?25h", dec, y + 1, x + 1);
  }
  if (sync_out) ostr("\x1b[?2026l");
  oflush();
}

static void resize_screen(void) {
  struct winsize ws;
  if (ioctl(1, TIOCGWINSZ, &ws) == 0 && ws.ws_col > 0) { W = ws.ws_col; H = ws.ws_row; }
  free(front); free(back);
  front = calloc((size_t)(W * H), sizeof(Cell)); back = calloc((size_t)(W * H), sizeof(Cell));
  full = 1; dirty = 1;
  say("size %d %d\n", W, H);
}

// ---- input from bb ----
static int hexrgb(const char *s, uint8_t *r, uint8_t *g, uint8_t *b) {
  if (!s || *s == '-' ) return 0;
  unsigned v; if (sscanf(s, "%6x", &v) != 1) return 0;
  *r = (uint8_t)(v >> 16); *g = (uint8_t)(v >> 8); *b = (uint8_t)v; return 1;
}

static uint32_t utf8_next(const char **s) {
  const unsigned char *p = (const unsigned char *)*s; uint32_t c = *p; int n = 1;
  if (c >= 0xF0) { c &= 0x07; n = 4; } else if (c >= 0xE0) { c &= 0x0F; n = 3; } else if (c >= 0xC0) { c &= 0x1F; n = 2; }
  for (int i = 1; i < n && p[i]; i++) c = (c << 6) | (p[i] & 0x3F);
  *s += n; return c;
}

// Overlay lines: ROW COL FG BG FLAGS<TAB>TEXT, one cell per codepoint, so
// bb measures text in codepoints. Wide characters in chrome are not used.
static void set_spans(char *payload, size_t len) {
  nspans = 0;
  char *line = payload, *end = payload + len;
  while (line < end) {
    char *nl = memchr(line, '\n', (size_t)(end - line)); if (!nl) nl = end; *nl = 0;
    char *tab = strchr(line, '\t');
    if (tab) {
      *tab = 0;
      int row, col, flags; char fg[16], bg[16];
      if (sscanf(line, "%d %d %15s %15s %d", &row, &col, fg, bg, &flags) == 5) {
        const char *t = tab + 1;
        while (*t) {
          if (nspans == capspans) { capspans = capspans * 2 + 256; spans = realloc(spans, capspans * sizeof(Span)); }
          Span *s = &spans[nspans++];
          s->row = row; s->col = col++; blank(&s->c);
          s->c.flags = (uint8_t)flags;
          if (hexrgb(fg, &s->c.fr, &s->c.fg, &s->c.fb)) s->c.fgk = 2;
          if (hexrgb(bg, &s->c.br, &s->c.bg, &s->c.bb)) s->c.bgk = 2;
          uint32_t c = utf8_next(&t);
          if (c != ' ') { s->c.cp[0] = c; s->c.n = 1; }
        }
      }
    }
    line = nl + 1;
  }
}

static void set_layout(char *payload, size_t len) {
  for (int i = 0; i < MAXP; i++) panes[i].shown = 0;
  norder = 0;
  char *line = payload, *end = payload + len;
  while (line < end) {
    char *nl = memchr(line, '\n', (size_t)(end - line)); if (!nl) nl = end; *nl = 0;
    char id[128]; int x, y, w, h;
    if (sscanf(line, "%127s %d %d %d %d", id, &x, &y, &w, &h) == 5) {
      Pane *p = find(id);
      if (p && !p->shown && norder < MAXP) { p->x = x; p->y = y; p->w = w; p->h = h; p->shown = 1; order[norder++] = p; }
    }
    line = nl + 1;
  }
}

static void text_reply(const char *req, Pane *p) {
  if (!p || pthread_mutex_trylock(&p->mu) != 0) { say("t %s 0 0\n.\n", req); return; }
  if (!p->ready) { pthread_mutex_unlock(&p->mu); say("t %s 0 0\n.\n", req); return; }
  ghostty_render_state_update(p->rs, p->term);
  // This update took rows' dirty marks that the next frame needs.
  p->pc_stale = 1;
  ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &p->rows);
  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  // Reuse the frame buffer helpers to build the reply.
  size_t keep = on;
  ofmt("t %s %u %u\n", req, cols, rows);
  while (ghostty_render_state_row_iterator_next(p->rows)) {
    ghostty_render_state_row_get(p->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &p->cells);
    while (ghostty_render_state_row_cells_next(p->cells)) {
      GhosttyCell raw = 0; GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
      ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw);
      ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
      if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) continue;
      uint32_t len = 0;
      ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &len);
      if (!len) { ostr(" "); continue; }
      uint32_t cps[16]; GhosttyCodepointBuffer gb = { .ptr = cps, .cap = 16 };
      ghostty_render_state_row_cells_get(p->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, &gb);
      for (size_t i = 0; i < gb.len && i < 16; i++) outf8(cps[i] == '\n' ? ' ' : cps[i]);
    }
    ostr("\n");
  }
  ostr(".\n");
  pthread_mutex_unlock(&p->mu);
  dirty = 1;  // the update above consumed the dirty state; redraw everything
  full = 1;
  pthread_mutex_lock(&ctl_mu);
  fwrite(ob + keep, 1, on - keep, ctl); fflush(ctl);
  pthread_mutex_unlock(&ctl_mu);
  on = keep;
}

static void handle(int type, char *pl, size_t len) {
  switch (type) {
    case 'o': pane_open(pl); break;
    case 'd': {  // ID NUL BYTES
      char *z = memchr(pl, 0, len); if (!z) break;
      Pane *p = find(pl); if (!p || !p->open || p->failed) break;
      qput(p, (uint8_t *)z + 1, len - (size_t)(z + 1 - pl));
      if (!p->ready) dirty = 1;  // the placeholder counts the bytes
      break;
    }
    case 'r': {  // ID COLS ROWS
      char id[128]; int c, r;
      if (sscanf(pl, "%127s %d %d", id, &c, &r) == 3) {
        Pane *p = find(id);
        if (p) {
          // In a stream, at its place among the bytes, for the pane's thread.
          pthread_mutex_lock(&p->qmu);
          int queued = p->open && !p->failed;
          if (queued) {
            if (p->nsz == (int)(sizeof p->sz / sizeof p->sz[0])) p->nsz--;  // the newest is replaced
            p->sz[p->nsz].at = p->got; p->sz[p->nsz].c = c; p->sz[p->nsz].r = r; p->nsz++;
            pthread_cond_signal(&p->qcv);
          }
          pthread_mutex_unlock(&p->qmu);
          if (!queued) {
            pthread_mutex_lock(&p->mu);
            if (p->term) ghostty_terminal_resize(p->term, (uint16_t)c, (uint16_t)r, 0, 0);
            pthread_mutex_unlock(&p->mu);
          }
          dirty = 1;
        }
      }
      break;
    }
    case 's': {  // ID DELTA | ID bottom
      char id[128], how[32];
      if (sscanf(pl, "%127s %31s", id, how) == 2) {
        Pane *p = find(id);
        if (p) {
          GhosttyTerminalScrollViewport sv; memset(&sv, 0, sizeof sv);
          if (!strcmp(how, "bottom")) sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM;
          else { sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_DELTA; sv.value.delta = atol(how); }
          if (pthread_mutex_trylock(&p->mu) == 0) { if (p->term) ghostty_terminal_scroll_viewport(p->term, sv); pthread_mutex_unlock(&p->mu); }
          dirty = 1;
        }
      }
      break;
    }
    case 'x': { Pane *p = find(pl); if (p) pane_close(p); dirty = 1; break; }
    case 'l': set_layout(pl, len); view_open = 1; dirty = 1; break;
    case 'v': set_spans(pl, len); view_open = 1; dirty = 1; break;
    case 'k': view_open = 0; dirty = 1; break;
    case 'c': {  // focus ID | at X Y | hide
      char how[16], a[128]; int y;
      if (sscanf(pl, "%15s %127s %d", how, a, &y) >= 1) {
        if (!strcmp(how, "focus")) { cur_mode = 1; snprintf(cur_pane, sizeof cur_pane, "%s", a); }
        else if (!strcmp(how, "at")) { cur_mode = 2; cx = atoi(a); cy = y; }
        else cur_mode = 0;
      }
      view_open = 1;
      dirty = 1;
      break;
    }
    case 't': {  // REQ ID
      char req[64], id[128];
      if (sscanf(pl, "%63s %127s", req, id) == 2) text_reply(req, find(id));
      break;
    }
    case 'f': full = 1; dirty = 1; break;
    // Raw bytes for the terminal itself, between frames: OSC 52 copies,
    // OSC 2 titles, OSC 777 notifications.
    case 'w': oput(pl, len); oflush(); break;
  }
}

// ---- terminal setup ----
static void restore(void) {
  const char *s = "\x1b[<u\x1b[?2004l\x1b[?1006l\x1b[?1002l\x1b[?1004l\x1b[0 q\x1b[0m\x1b[?25h\x1b[?1049l";
  ssize_t r = write(1, s, strlen(s)); (void)r;
  if (have_saved) tcsetattr(1, TCSANOW, &saved);
}
static void on_winch(int sig) { (void)sig; char b = 1; ssize_t r = write(winch[1], &b, 1); (void)r; }
static void on_term(int sig) { (void)sig; restore(); _exit(0); }

static long now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000L; }

int main(void) {
  ctl = stderr;
  const char *sync_env = getenv("REXG_SYNC");
  if (sync_env && !strcmp(sync_env, "0")) sync_out = 0;
  const char *trace = getenv("REXG_TRACE");
  if (trace && *trace) trace_fd = open(trace, O_WRONLY | O_CREAT | O_APPEND, 0600);
  if (pipe(wake) < 0 || pipe(winch) < 0) return 1;
  fcntl(wake[0], F_SETFL, O_NONBLOCK); fcntl(winch[0], F_SETFL, O_NONBLOCK);
  if (tcgetattr(1, &saved) == 0) {
    have_saved = 1;
    struct termios t = saved; cfmakeraw(&t); tcsetattr(1, TCSANOW, &t);
  }
  signal(SIGWINCH, on_winch); signal(SIGTERM, on_term); signal(SIGHUP, on_term); signal(SIGPIPE, SIG_IGN);
  // Alternate screen, SGR mouse with drags, focus events, bracketed paste,
  // and the Kitty keyboard protocol (disambiguate, every key as an escape
  // code, with its text), so bb sees every chord exactly.
  const char *init = "\x1b[?1049h\x1b[?25l\x1b[?1002h\x1b[?1006h\x1b[?1004h\x1b[?2004h\x1b[>25u";
  write_all(1, (const uint8_t *)init, strlen(init));
  resize_screen();
  uint8_t hdr[5]; size_t hn = 0; char *pl = NULL; size_t plen = 0, pn = 0, pcap = 0;
  uint8_t inbuf[65536];
  long last = 0, held_since = 0;
  for (;;) {
    // At most one frame every 8 ms: a burst of output costs one redraw.
    long wait = dirty ? 8 - (now_ms() - last) : -1;
    if (dirty && wait < 0) wait = 0;
    struct pollfd fds[3] = { { 0, POLLIN, 0 }, { wake[0], POLLIN, 0 }, { winch[0], POLLIN, 0 } };
    int r = poll(fds, 3, (int)wait);
    if (r < 0 && errno != EINTR) break;
    if (fds[2].revents & POLLIN) { char b[64]; while (read(winch[0], b, sizeof b) > 0) {} resize_screen(); }
    if (fds[1].revents & POLLIN) { char b[256]; while (read(wake[0], b, sizeof b) > 0) {} }
    if (fds[0].revents & (POLLIN | POLLHUP)) {
      ssize_t n = read(0, inbuf, sizeof inbuf);
      if (n <= 0) break;
      for (ssize_t i = 0; i < n;) {
        if (hn < 5) { hdr[hn++] = inbuf[i++]; if (hn == 5) { plen = ((size_t)hdr[1] << 24) | ((size_t)hdr[2] << 16) | ((size_t)hdr[3] << 8) | hdr[4]; pn = 0; if (plen + 1 > pcap) { pcap = plen + 1; pl = realloc(pl, pcap); } } }
        if (hn == 5) {
          size_t take = plen - pn; if ((size_t)(n - i) < take) take = (size_t)(n - i);
          memcpy(pl + pn, inbuf + i, take); pn += take; i += (ssize_t)take;
          if (pn == plen) {
            pl[plen] = 0;
            if (hdr[0] == 'q') { restore(); return 0; }
            handle(hdr[0], pl, plen);
            hn = 0;
          }
        }
      }
    }
    // bb sends a view as a layout, overlay and cursor message and then 'k':
    // draw once the view is whole and what is already written has been read,
    // so no frame shows a new layout or overlay with the old cursor (T223),
    // but at least every 50 ms.
    if (dirty && (hn || view_open || poll(fds, 1, 0) > 0)) {
      if (!held_since) held_since = now_ms();
      if (now_ms() - held_since < 50) continue;
    }
    held_since = 0;
    if (dirty && now_ms() - last >= 8) { dirty = 0; last = now_ms(); frame(); }
  }
  restore();
  return 0;
}
