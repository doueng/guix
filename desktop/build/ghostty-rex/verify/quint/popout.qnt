// -*- mode: quint; -*-
// A popped-out pane's size: who sizes one Rex pane while it
// is shown both in the main ghostty-rex window and in a Ghostty window of
// its own (ghostty-rex pane, src/rexg/pane.clj).
//
// Rex lets one client own a block; only the owner can resize it, and
// session.own_block with mode take makes the caller the owner at once. The
// main window takes and resizes each pane it lays out whenever its layout
// size changes (main.clj sync-sizes!). The pane window takes the block and
// sizes it to itself when it opens, and looks every 2 s to put its size
// back after a client that took the block resized it.
//
// The rule, a constant so the other behaviour can be checked too:
// - MainSkips: the main window keeps the blocks it popped out in a set and
//   skips them; when the pop-out's process ends, it drops the block from
//   the set and forgets its last size, so the next layout sizes it again.
module popout {
  const MainSkips: bool

  type Who = Main | Pane | Other | Nobody
  // M: the main window's tile; P: the pane window; X: another client's.
  type Size = M | P | X

  var owner: Who
  var bsize: Size   // the block's size
  var paneOpen: bool     // the pane window's relay runs
  var popped: bool       // the block is in the main window's popped set
  var cacheOk: bool      // the main window's last size for the block is current
  var otherDone: bool    // another client took the block (once)

  action init = all {
    owner' = Main, bsize' = M, paneOpen' = false, popped' = false, cacheOk' = true, otherDone' = false,
  }

  // M-x Pane: pop out. The relay takes the block and sizes it to its window
  // before it shows the stream.
  action popOut = all {
    not(paneOpen), not(popped),
    paneOpen' = true, popped' = MainSkips, owner' = Pane, bsize' = P,
    cacheOk' = cacheOk, otherDone' = otherDone,
  }

  // The main window's layout changes (a tab shown again, a split, a resize).
  action layoutChange = all {
    cacheOk' = false,
    owner' = owner, bsize' = bsize, paneOpen' = paneOpen, popped' = popped, otherDone' = otherDone,
  }

  // sync-sizes!: take and resize each laid-out block whose size changed.
  action mainSync = all {
    not(cacheOk),
    if (MainSkips and popped) all { owner' = owner, bsize' = bsize }
    else all { owner' = Main, bsize' = M },
    cacheOk' = true,
    paneOpen' = paneOpen, popped' = popped, otherDone' = otherDone,
  }

  // Another client takes the block and resizes it.
  action otherTakes = all {
    not(otherDone),
    owner' = Other, bsize' = X, otherDone' = true,
    paneOpen' = paneOpen, popped' = popped, cacheOk' = cacheOk,
  }

  // The relay's 2 s look: a size not its own is put back.
  action paneWatch = all {
    paneOpen, bsize != P,
    owner' = Pane, bsize' = P,
    paneOpen' = paneOpen, popped' = popped, cacheOk' = cacheOk, otherDone' = otherDone,
  }

  // The pane window closes; its client leaves, and the block has no owner.
  action closePane = all {
    paneOpen,
    paneOpen' = false, owner' = if (owner == Pane) Nobody else owner,
    bsize' = bsize, popped' = popped, cacheOk' = cacheOk, otherDone' = otherDone,
  }

  // The main window sees the pop-out's process end.
  action mainNotified = all {
    popped, not(paneOpen),
    popped' = false, cacheOk' = false,
    owner' = owner, bsize' = bsize, paneOpen' = paneOpen, otherDone' = otherDone,
  }

  action step = any { popOut, layoutChange, mainSync, otherTakes, paneWatch, closePane, mainNotified }

  // The main window never takes the block from an open pane window.
  val mainLeavesPane = paneOpen implies owner != Main
  // While the pane window owns the block, the block has its size.
  val paneHasItsSize = (paneOpen and owner == Pane) implies bsize == P
  // Once the pane window is gone and the main window caught up, the block
  // is the main window's at its size.
  val settledMain = (not(paneOpen) and not(popped) and cacheOk and not(otherDone)) implies (owner == Main and bsize == M)
  val typeOK = (popped implies MainSkips)
}

// The main window skips what it popped out, as the code does: every rule holds.
module fixed {
  import popout(MainSkips = true).*

  // Pop out, the main layout changes while the pane is open, the window
  // closes: the main window sizes the pane again.
  run popOutAndBackTest =
    init
      .then(popOut).then(layoutChange).then(mainSync)
      .expect(mainLeavesPane and bsize == P)
      .then(closePane).then(mainNotified).then(mainSync)
      .expect(owner == Main and bsize == M)

  // A client that takes the block is undone within the pane's next look.
  run otherIsUndoneTest =
    init
      .then(popOut).then(otherTakes).then(paneWatch)
      .expect(owner == Pane and bsize == P)
}

// A main window that takes every block it lays out: it takes the pane back
// from the pane window at its next layout change.
module naive {
  import popout(MainSkips = false).*

  run mainTakesItBackTest =
    init
      .then(popOut).then(layoutChange).then(mainSync)
      .expect(not(mainLeavesPane))
}
