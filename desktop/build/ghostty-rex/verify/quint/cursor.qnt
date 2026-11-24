// -*- mode: quint; -*-
// The cursor through ghostty-rex (T223): which cursor Ghostty shows for the
// focused pane, its shape and whether it shows at all.
//
// A program asks for a cursor with DECSCUSR (shape) and DECTCEM (shown or
// hidden) in its pane. Rex's terminal for the pane holds what it asked for
// last; both settings are terminal-wide, so entering or leaving the
// alternate screen (Emacs, nvim, jjui) is just more requests. The bb client
// (rexg.main, rexg.screen) decides what the window shows: a tab's panes, an
// overlay (the M-x minibuffer, which-key, tiny fingers) and where the cursor
// goes (rexg-render's 'c' message: a pane, the minibuffer, or hidden). It
// writes those messages into a pipe to rexg-render
// (desktop/build/ghostty-rex/csrc/rexg-render.c), which keeps a terminal per
// streamed pane, fed by Rex's snapshot and then the live bytes, and draws
// frames into Ghostty: each frame hides the cursor, draws, and shows the
// focused pane's cursor where and as its terminal has it.
//
// Three fixes, each a constant so the old behaviour can be checked too:
// - Batched: bb ends each view with a 'k' message, and rexg-render draws
//   only once a view is whole (a message's last flag), so no frame mixes a
//   new layout or overlay with the old cursor. Reading what the pipe holds
//   was not enough: a large view reaches the pipe in pieces.
// - Captured: draw_pane reads the cursor under the pane's lock with its
//   cells. Before, frame() read it with a second trylock, and a pane thread
//   taking the lock in between hid the cursor while the cells drew.
// - FreshOnly: a pane shown again keeps its last screen until Rex's new
//   snapshot is in, as T210 wants, but not its last cursor, which may be
//   stale: the program may have changed mode meanwhile.
// DirtyAfterLock: each time a pane thread releases its lock it asks for a
// frame. The write of the bytes that follow a snapshot did not.
//
// Panes are 1 and 2, each alone in tab 1 and tab 2. Shapes are the person's
// underscore (Under, also what CSI 0 q gives) and a bar; a block request
// draws as Ghostty's configured cursor too, since libghostty-vt reports
// block for both.
module cursor {
  const Batched: bool
  const Captured: bool
  const FreshOnly: bool
  const DirtyAfterLock: bool

  type Shape = Under | Bar
  type Req = { shape: Shape, vis: bool }
  type Overlay = NoOverlay | Mini | WhichKey
  // Where bb puts the cursor: a pane, the minibuffer, nowhere.
  type Target = TFocus(int) | TAt | THide
  type Msg = MLayout((int, Overlay)) | MCursor(Target)
  // What Ghostty shows: no cursor, the minibuffer's, or a pane's.
  type Drawn = DHidden | DAt | DPane((int, Shape))

  pure val Panes = Set(1, 2)
  pure val Tabs = Set(1, 2)
  pure val Shapes = Set(Under, Bar)
  pure val Overlays = Set(NoOverlay, Mini, WhichKey)
  pure val MaxQueue = 4

  pure def paneOf(t: int): int = t
  pure def targetFor(t: int, ov: Overlay): Target =
    match ov { | Mini => TAt | WhichKey => THide | NoOverlay => TFocus(paneOf(t)) }
  pure def cursorOf(p: int, r: Req): Drawn = if (r.vis) DPane((p, r.shape)) else DHidden

  // Rex: each pane's terminal, the truth.
  var rex: int -> Req
  // bb: its view, and the pipe to rexg-render.
  var tab: int
  var overlay: Overlay
  var queue: List[{ msg: Msg, last: bool }]
  // rexg-render.
  var shownTab: int        // the layout it draws
  var ov: Overlay          // the overlay it draws
  var target: Target
  var midBatch: bool       // it has read part of a view
  var streaming: int -> bool
  var have: int -> bool    // a terminal to draw (ready)
  var fresh: int -> bool   // from the current stream's snapshot
  var loc: int -> Req      // that terminal's cursor
  var pend: int -> bool    // bytes from Rex not yet written to it
  var held: Set[int]       // pane threads holding their lock
  var cap: int -> Drawn    // the cursor draw_pane read with the cells
  var dirty: bool
  // Ghostty.
  var drawn: Drawn
  var mixed: bool          // the last frame mixed two views
  var stale: bool          // the last frame showed a cursor from an old terminal

  // What the person should see once everything has arrived.
  def expected: Drawn =
    match overlay {
      | Mini => DAt
      | WhichKey => DHidden
      | NoOverlay => cursorOf(paneOf(tab), rex.get(paneOf(tab)))
    }

  action init = all {
    rex' = Panes.mapBy(_ => { shape: Under, vis: true }),
    tab' = 1, overlay' = NoOverlay, queue' = [],
    shownTab' = 1, ov' = NoOverlay, target' = TFocus(1), midBatch' = false,
    streaming' = Panes.mapBy(p => p == 1),
    have' = Panes.mapBy(p => p == 1),
    fresh' = Panes.mapBy(p => p == 1),
    loc' = Panes.mapBy(_ => { shape: Under, vis: true }),
    pend' = Panes.mapBy(_ => false),
    held' = Set(),
    cap' = Panes.mapBy(p => if (p == 1) DPane((1, Under)) else DHidden),
    dirty' = false,
    drawn' = DPane((1, Under)), mixed' = false, stale' = false,
  }

  action unchangedRexg = all {
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, have' = have, fresh' = fresh, loc' = loc, pend' = pend,
    held' = held, cap' = cap, dirty' = dirty,
  }
  action unchangedGhostty = all { drawn' = drawn, mixed' = mixed, stale' = stale }
  action unchangedBb = all { tab' = tab, overlay' = overlay, queue' = queue }

  // A program asks for a cursor (a mode change, or entering or leaving the
  // alternate screen); also in a pane no client streams, as another client
  // of the same Rex session can make it.
  action request(p: int, r: Req): bool = all {
    rex.get(p) != r,
    rex' = rex.set(p, r),
    pend' = pend.set(p, streaming.get(p) and fresh.get(p)),
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, have' = have, fresh' = fresh, loc' = loc,
    held' = held, cap' = cap, dirty' = dirty,
    unchangedBb, unchangedGhostty,
  }

  // bb's view changes: it sends the layout (with the overlay's spans) and
  // the cursor as one view.
  action view(t: int, o: Overlay): bool = all {
    (t, o) != (tab, overlay),
    queue.length() + 2 <= MaxQueue,
    tab' = t, overlay' = o,
    queue' = queue.append({ msg: MLayout((t, o)), last: false }).append({ msg: MCursor(targetFor(t, o)), last: true }),
    rex' = rex, unchangedRexg, unchangedGhostty,
  }

  // rexg-render reads the next message. A new layout opens the shown
  // pane's stream (its last screen stays until the snapshot is in) and
  // closes the other's.
  action read = all {
    queue.length() > 0,
    val m = queue.head()
    all {
      queue' = queue.tail(),
      midBatch' = not(m.last),
      dirty' = true,
      match m.msg {
        | MLayout(lo) => all {
            shownTab' = lo._1, ov' = lo._2, target' = target,
            streaming' = Panes.mapBy(p => p == paneOf(lo._1)),
            fresh' = Panes.mapBy(p => fresh.get(p) and p == paneOf(lo._1) and streaming.get(p)),
            pend' = Panes.mapBy(p => pend.get(p) and p == paneOf(lo._1) and streaming.get(p)),
            held' = held.filter(p => p == paneOf(lo._1) and streaming.get(p)),
            // A pane shown again drops the cursor it had (FreshOnly).
            cap' = Panes.mapBy(p => if (FreshOnly and not(fresh.get(p) and p == paneOf(lo._1) and streaming.get(p))) DHidden
                                    else cap.get(p)),
          }
        | MCursor(tg) => all {
            shownTab' = shownTab, ov' = ov, target' = tg,
            streaming' = streaming, fresh' = fresh, pend' = pend, held' = held, cap' = cap,
          }
      },
    },
    have' = have, loc' = loc,
    tab' = tab, overlay' = overlay, rex' = rex, unchangedGhostty,
  }

  // The stream's snapshot is in: the pane gets a new terminal as Rex has it.
  action ready(p: int): bool = all {
    streaming.get(p), not(fresh.get(p)), not(held.contains(p)),
    have' = have.set(p, true), fresh' = fresh.set(p, true),
    loc' = loc.set(p, rex.get(p)), pend' = pend.set(p, false),
    dirty' = true,
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, held' = held, cap' = cap,
    rex' = rex, unchangedBb, unchangedGhostty,
  }

  // The pane thread writes Rex's bytes to the terminal and asks for a frame.
  action deliver(p: int): bool = all {
    pend.get(p), not(held.contains(p)),
    loc' = loc.set(p, rex.get(p)), pend' = pend.set(p, false), dirty' = true,
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, have' = have, fresh' = fresh, held' = held, cap' = cap,
    rex' = rex, unchangedBb, unchangedGhostty,
  }

  // A pane thread holds its lock for a while (a piece of a snapshot's
  // history or images, the bytes after it) and lets go.
  action hold(p: int): bool = all {
    streaming.get(p), not(held.contains(p)),
    held' = held.union(Set(p)),
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, have' = have, fresh' = fresh, loc' = loc, pend' = pend,
    cap' = cap, dirty' = dirty,
    rex' = rex, unchangedBb, unchangedGhostty,
  }
  action release(p: int): bool = all {
    held.contains(p),
    held' = held.exclude(Set(p)),
    dirty' = (dirty or DirtyAfterLock),
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, have' = have, fresh' = fresh, loc' = loc, pend' = pend,
    cap' = cap,
    rex' = rex, unchangedBb, unchangedGhostty,
  }

  // The cursor a pane's terminal gives, as draw_pane reads it.
  def paneCursor(p: int): Drawn =
    if (have.get(p) and (fresh.get(p) or not(FreshOnly))) cursorOf(p, loc.get(p)) else DHidden

  // A frame. draw_pane skips a pane whose lock is held (it keeps the old
  // cells and asks for another frame). Without Captured, frame() then reads
  // the cursor with a second trylock, which fails when the pane thread took
  // the lock in between (grab), drawing no cursor.
  action frame(grab: bool): bool = all {
    dirty,
    not(Batched) or queue.length() == 0,
    val p = paneOf(shownTab)
    val busy = held.contains(p)
    val c = if (busy) cap.get(p) else paneCursor(p)
    val grabbed = grab and not(Captured) and not(busy) and streaming.get(p)
    val d = match target {
      | TAt => DAt
      | THide => DHidden
      | TFocus(f) => if (f != p) DHidden
                     else if (Captured) c
                     else if (busy or grabbed) DHidden
                     else c
    }
    all {
      cap' = cap.set(p, c),
      held' = if (grabbed) held.union(Set(p)) else held,
      dirty' = busy,
      drawn' = d,
      mixed' = midBatch,
      stale' = match d { | DPane(pc) => not(fresh.get(pc._1)) | _ => false },
    },
    shownTab' = shownTab, ov' = ov, target' = target, midBatch' = midBatch,
    streaming' = streaming, have' = have, fresh' = fresh, loc' = loc, pend' = pend,
    rex' = rex, unchangedBb,
  }

  action step = any {
    nondet p = Panes.oneOf()
    nondet s = Shapes.oneOf()
    nondet v = Set(true, false).oneOf()
    request(p, { shape: s, vis: v }),
    nondet t = Tabs.oneOf()
    nondet o = Overlays.oneOf()
    view(t, o),
    read,
    nondet p = Panes.oneOf()
    any { ready(p), deliver(p), hold(p), release(p) },
    nondet g = Set(true, false).oneOf()
    frame(g),
  }

  // Everything has arrived and been drawn.
  def quiet: bool = and {
    queue.length() == 0, not(dirty), held == Set(),
    Panes.forall(p => not(pend.get(p))),
    fresh.get(paneOf(shownTab)),
  }

  // Once quiet, Ghostty shows the focused pane's cursor as its program
  // asked, the minibuffer's while M-x is open, and none under which-key:
  // so closing an overlay gives the pane's cursor back.
  val settledCursor = quiet implies drawn == expected
  // No frame draws half of a view: a new layout or overlay with the old
  // cursor, or the old with the new.
  val noMixedFrame = not(mixed)
  // A pane shown again shows no cursor until its new snapshot is in.
  val noStaleCursor = not(stale)
  val typeOK = and {
    Tabs.contains(tab), Tabs.contains(shownTab), queue.length() <= MaxQueue,
    held.subseteq(Panes),
  }
}

// ghostty-rex as T223 leaves it: every rule holds.
module fixed {
  import cursor(Batched = true, Captured = true, FreshOnly = true, DirtyAfterLock = true).*

  // Leaving M-x gives the pane's underscore back.
  run minibufferRestoresTest =
    init
      .then(view(1, Mini)).then(read).then(read).then(frame(false))
      .expect(drawn == DAt)
      .then(view(1, NoOverlay)).then(read).then(read).then(frame(false))
      .expect(drawn == DPane((1, Under)) and settledCursor)

  // Insert mode hides the cursor; normal mode brings the underscore back.
  run modeSwitchTest =
    init
      .then(request(1, { shape: Under, vis: false })).then(deliver(1)).then(frame(false))
      .expect(drawn == DHidden and settledCursor)
      .then(request(1, { shape: Under, vis: true })).then(deliver(1)).then(frame(false))
      .expect(drawn == DPane((1, Under)) and settledCursor)
}

// rexg-render before T223: each of these traces breaks a rule.
module before {
  import cursor(Batched = false, Captured = false, FreshOnly = false, DirtyAfterLock = false).*

  // A frame between the layout and the cursor message draws the which-key
  // overlay with the pane's cursor still showing.
  run mixedFrameTest =
    init
      .then(view(1, WhichKey)).then(read).then(frame(false))
      .expect(not(noMixedFrame) and drawn == DPane((1, Under)))

  // The pane thread takes the lock between draw_pane and the cursor read:
  // the frame hides the cursor, and the bytes after a snapshot ask for no
  // frame when the thread lets go, so it stays hidden.
  run lostCursorTest =
    init
      .then(request(1, { shape: Bar, vis: true })).then(deliver(1)).then(frame(true))
      .then(release(1))
      .expect(quiet and drawn == DHidden and not(settledCursor))

  // Back on a tab whose program went to insert mode meanwhile: its old
  // underscore shows before the new snapshot is in.
  run staleCursorTest =
    init
      .then(view(2, NoOverlay)).then(read).then(read).then(ready(2)).then(frame(false))
      .then(request(1, { shape: Under, vis: false }))
      .then(view(1, NoOverlay)).then(read).then(read).then(frame(false))
      .expect(drawn == DPane((1, Under)) and not(noStaleCursor))
}
