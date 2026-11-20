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
// (size COLS ROWS, ready ID, mode ID BITS, text replies, pane errors). Build: make ghostty-rex, or
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
  int pipe_w;
  pthread_t th;
  pthread_mutex_t mu;
  GhosttyTerminal term;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator rows;
  GhosttyRenderStateRowCells cells;
  int x, y, w, h, shown;
  int mouse;  // the program asked for mouse reports (last told to bb)
} Pane;

static Pane panes[MAXP];
static int W = 80, H = 24;
static Cell *front, *back;
static int wake[2], winch[2];
static FILE *ctl;
static pthread_mutex_t ctl_mu = PTHREAD_MUTEX_INITIALIZER;
static volatile int dirty = 1, full = 1;
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

typedef struct { int fd; uint8_t *fed; size_t len, cap; } Rd;
static bool rd(void *u, uint8_t *buf, size_t cap, size_t *out) {
  Rd *r = u;
  ssize_t n;
  do n = read(r->fd, buf, cap); while (n < 0 && errno == EINTR);
  if (n < 0) return false;
  if (r->len + (size_t)n > r->cap) { r->cap = (r->len + (size_t)n) * 2 + 4096; r->fed = realloc(r->fed, r->cap); }
  memcpy(r->fed + r->len, buf, (size_t)n); r->len += (size_t)n;
  *out = (size_t)n;
  return true;
}

typedef struct { Pane *p; int rfd; } Start;
static void *pane_thread(void *arg) {
  Start s = *(Start *)arg; free(arg);
  Pane *p = s.p;
  Rd r = { s.rfd, NULL, 0, 0 };
  GhosttySnapshotDecoder dec; GhosttyTerminal term = NULL;
  GhosttyReader reader = { rd, &r };
  if (ghostty_snapshot_decoder_new(NULL, &dec, reader) != GHOSTTY_SUCCESS ||
      ghostty_snapshot_decoder_decode(dec, &term) != GHOSTTY_SUCCESS) {
    say("err %s snapshot\n", p->id);
    pthread_mutex_lock(&p->mu); p->failed = 1; pthread_mutex_unlock(&p->mu);
    // Drain, so bb never blocks on a dead pane.
    uint8_t b[65536]; while (read(s.rfd, b, sizeof b) > 0) {}
    close(s.rfd); free(r.fed);
    return NULL;
  }
  size_t off = 0;
  ghostty_snapshot_decoder_get(dec, GHOSTTY_SNAPSHOT_DECODER_DATA_SOURCE_OFFSET, &off);
  pthread_mutex_lock(&p->mu);
  p->term = term;
  ghostty_render_state_new(NULL, &p->rs);
  ghostty_render_state_row_iterator_new(NULL, &p->rows);
  ghostty_render_state_row_cells_new(NULL, &p->cells);
  if (r.len > off) ghostty_terminal_vt_write(term, r.fed + off, r.len - off);
  p->ready = 1;
  pthread_mutex_unlock(&p->mu);
  ghostty_snapshot_decoder_free(dec);
  free(r.fed);
  say("ready %s\n", p->id);
  dirty = 1; poke();
  uint8_t buf[65536]; ssize_t n;
  for (;;) {
    n = read(s.rfd, buf, sizeof buf);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    pthread_mutex_lock(&p->mu);
    ghostty_terminal_vt_write(p->term, buf, (size_t)n);
    pthread_mutex_unlock(&p->mu);
    dirty = 1; poke();
  }
  close(s.rfd);
  return NULL;
}

static void pane_open(const char *id) {
  if (find(id)) return;
  for (int i = 0; i < MAXP; i++) if (!panes[i].used) {
    Pane *p = &panes[i];
    memset(p, 0, sizeof *p);
    snprintf(p->id, sizeof p->id, "%s", id);
    pthread_mutex_init(&p->mu, NULL);
    int fds[2];
    if (pipe(fds) < 0) { say("err %s pipe\n", id); return; }
    p->pipe_w = fds[1];
    p->used = 1;
    Start *s = malloc(sizeof *s); s->p = p; s->rfd = fds[0];
    pthread_create(&p->th, NULL, pane_thread, s);
    return;
  }
  say("err %s too-many-panes\n", id);
}

static void pane_close(Pane *p) {
  close(p->pipe_w);
  pthread_join(p->th, NULL);
  if (p->cells) ghostty_render_state_row_cells_free(p->cells);
  if (p->rows) ghostty_render_state_row_iterator_free(p->rows);
  if (p->rs) ghostty_render_state_free(p->rs);
  if (p->term) ghostty_terminal_free(p->term);
  pthread_mutex_destroy(&p->mu);
  p->used = 0;
  full = 1;
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
  pthread_mutex_lock(&p->mu);
  if (!p->ready || !p->term) {
    pthread_mutex_unlock(&p->mu);
    return;
  }
  ghostty_render_state_update(p->rs, p->term);
  ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &p->rows);
  int y = 0;
  while (ghostty_render_state_row_iterator_next(p->rows) && y < p->h) {
    ghostty_render_state_row_get(p->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &p->cells);
    int x = 0;
    while (ghostty_render_state_row_cells_next(p->cells) && x < p->w) {
      int sx = p->x + x, sy = p->y + y;
      if (sx < 0 || sy < 0 || sx >= W || sy >= H) { x++; continue; }
      Cell *c = &back[sy * W + sx];
      blank(c);
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
        // A wide character that does not fit the pane is drawn as a blank.
        if (x + 1 >= p->w || sx + 1 >= W) { x++; continue; }
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
    y++;
  }
  ghostty_render_state_clean(p->rs);
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

static void pane_cursor(Pane *p, int *ok, int *x, int *y, int *style) {
  *ok = 0;
  pthread_mutex_lock(&p->mu);
  if (p->ready && p->term) {
    GhosttyRenderStateCursor cu = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
    if (ghostty_render_state_get(p->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR, &cu) == GHOSTTY_SUCCESS &&
        cu.viewport_has_value && cu.visible && cu.viewport_x < p->w && cu.viewport_y < p->h) {
      *ok = 1; *x = p->x + cu.viewport_x; *y = p->y + cu.viewport_y;
      *style = (int)cu.visual_style;
    }
  }
  pthread_mutex_unlock(&p->mu);
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
  for (int i = 0; i < MAXP; i++) if (panes[i].used && panes[i].shown) draw_pane(&panes[i]);
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
  ostr("\x1b[?2026h\x1b[?25l");
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
  if (cur_mode == 1) { Pane *p = find(cur_pane); if (p && p->shown) pane_cursor(p, &ok, &x, &y, &cs); }
  else if (cur_mode == 2) { ok = 1; x = cx; y = cy; cs = 1; }
  if (ok) {
    // libghostty's cursor styles: 0 bar, 1 block, 2 underline, 3 hollow
    // block. A block, the default, is Ghostty's configured cursor (CSI 0 q),
    // so the person's cursor-style holds unless a program asks for a bar or
    // an underline.
    int dec = cs == 0 ? 6 : cs == 2 ? 4 : 0;
    ofmt("\x1b[%d q\x1b[%d;%dH\x1b[?25h", dec, y + 1, x + 1);
  }
  ostr("\x1b[?2026l");
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
  char *line = payload, *end = payload + len;
  while (line < end) {
    char *nl = memchr(line, '\n', (size_t)(end - line)); if (!nl) nl = end; *nl = 0;
    char id[128]; int x, y, w, h;
    if (sscanf(line, "%127s %d %d %d %d", id, &x, &y, &w, &h) == 5) {
      Pane *p = find(id);
      if (p) { p->x = x; p->y = y; p->w = w; p->h = h; p->shown = 1; }
    }
    line = nl + 1;
  }
}

static void text_reply(const char *req, Pane *p) {
  if (!p) { say("t %s 0 0\n.\n", req); return; }
  pthread_mutex_lock(&p->mu);
  if (!p->ready) { pthread_mutex_unlock(&p->mu); say("t %s 0 0\n.\n", req); return; }
  ghostty_render_state_update(p->rs, p->term);
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
      Pane *p = find(pl); if (!p || p->failed) break;
      write_all(p->pipe_w, (uint8_t *)z + 1, len - (size_t)(z + 1 - pl));
      break;
    }
    case 'r': {  // ID COLS ROWS
      char id[128]; int c, r;
      if (sscanf(pl, "%127s %d %d", id, &c, &r) == 3) {
        Pane *p = find(id);
        if (p) { pthread_mutex_lock(&p->mu); if (p->term) ghostty_terminal_resize(p->term, (uint16_t)c, (uint16_t)r, 0, 0); pthread_mutex_unlock(&p->mu); dirty = 1; }
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
          pthread_mutex_lock(&p->mu); if (p->term) ghostty_terminal_scroll_viewport(p->term, sv); pthread_mutex_unlock(&p->mu);
          dirty = 1;
        }
      }
      break;
    }
    case 'x': { Pane *p = find(pl); if (p) pane_close(p); dirty = 1; break; }
    case 'l': set_layout(pl, len); dirty = 1; break;
    case 'v': set_spans(pl, len); dirty = 1; break;
    case 'c': {  // focus ID | at X Y | hide
      char how[16], a[128]; int y;
      if (sscanf(pl, "%15s %127s %d", how, a, &y) >= 1) {
        if (!strcmp(how, "focus")) { cur_mode = 1; snprintf(cur_pane, sizeof cur_pane, "%s", a); }
        else if (!strcmp(how, "at")) { cur_mode = 2; cx = atoi(a); cy = y; }
        else cur_mode = 0;
      }
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
  long last = 0;
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
    if (dirty && now_ms() - last >= 8) { dirty = 0; last = now_ms(); frame(); }
  }
  restore();
  return 0;
}
