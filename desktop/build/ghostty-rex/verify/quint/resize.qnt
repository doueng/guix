// -*- mode: quint; -*-
// A pane's size through ghostty-rex: every byte a program writes
// reaches rexg-render's terminal for that pane at the size the program wrote
// it for.
//
// Rex resizes a pane's terminal when bb asks (com.superlogical.terminal.resize)
// and says so in the pane's data stream, as a resize message among the bytes;
// the program redraws for the new size after it. bb reads the stream on one
// thread per pane (rexg.rex/open-stream!) and writes to rexg-render's pipe:
// the bytes as 'd' frames, the size as an 'r' frame. rexg-render's main loop
// reads the pipe, queues a pane's bytes for that pane's thread, and the pane's
// thread writes them to the pane's terminal (csrc/rexg-render.c).
//
// Two rules, each a constant so the other order can be checked too:
// - BbInOrder: bb sends the 'r' frame from the stream's reader thread, in
//   order with the bytes. Without it the size goes through bb's main queue,
//   and bytes after it can reach the pipe first.
// - RenderInOrder: rexg-render queues the size with the pane's bytes, at its
//   place among them, and the pane's thread applies it there. Without it the
//   main loop resizes the terminal at once, ahead of bytes still queued.
//
// With neither, a redraw for 110x26 lands in a 90x20 terminal, and Emacs's
// old mode line stays on screen under its new cursor.
module resize {
  const BbInOrder: bool
  const RenderInOrder: bool

  type Item = Bytes(int) | Size(int)   // bytes written for a size; a new size
  pure val Sizes = Set(1, 2)
  pure val MaxQueue = 3

  // Rex: the pane's size, and the stream bb has not read yet.
  var rexSize: int
  var stream: List[Item]
  // bb: sizes waiting on its main queue (without BbInOrder).
  var mainq: List[Item]
  // The pipe to rexg-render, its pane queue, and the pane's terminal.
  var pipe: List[Item]
  var paneq: List[Item]
  var termSize: int
  // Some bytes were written to the terminal at a size they were not for.
  var misfit: bool

  action init = all {
    rexSize' = 1, stream' = List(), mainq' = List(), pipe' = List(),
    paneq' = List(), termSize' = 1, misfit' = false,
  }

  // The program writes for the size it has.
  action write = all {
    stream.length() < MaxQueue,
    stream' = stream.append(Bytes(rexSize)),
    rexSize' = rexSize, mainq' = mainq, pipe' = pipe, paneq' = paneq, termSize' = termSize, misfit' = misfit,
  }

  // bb asks Rex for a new size; Rex says so in the stream.
  action resize(s: int): bool = all {
    s != rexSize, stream.length() < MaxQueue,
    rexSize' = s, stream' = stream.append(Size(s)),
    mainq' = mainq, pipe' = pipe, paneq' = paneq, termSize' = termSize, misfit' = misfit,
  }

  // bb's reader thread takes the next item of the stream.
  action read = all {
    stream.length() > 0, pipe.length() < MaxQueue, mainq.length() < MaxQueue,
    val it = stream.head()
    match it {
      | Bytes(_) => all { pipe' = pipe.append(it), mainq' = mainq }
      | Size(_) => if (BbInOrder) all { pipe' = pipe.append(it), mainq' = mainq }
                   else all { pipe' = pipe, mainq' = mainq.append(it) }
    },
    stream' = stream.tail(),
    rexSize' = rexSize, paneq' = paneq, termSize' = termSize, misfit' = misfit,
  }

  // bb's main thread sends a size from its queue.
  action relay = all {
    mainq.length() > 0, pipe.length() < MaxQueue,
    pipe' = pipe.append(mainq.head()), mainq' = mainq.tail(),
    rexSize' = rexSize, stream' = stream, paneq' = paneq, termSize' = termSize, misfit' = misfit,
  }

  // rexg-render's main loop reads the pipe.
  action take = all {
    pipe.length() > 0, paneq.length() < MaxQueue,
    val it = pipe.head()
    match it {
      | Bytes(_) => all { paneq' = paneq.append(it), termSize' = termSize }
      | Size(s) => if (RenderInOrder) all { paneq' = paneq.append(it), termSize' = termSize }
                   else all { paneq' = paneq, termSize' = s }
    },
    pipe' = pipe.tail(),
    rexSize' = rexSize, stream' = stream, mainq' = mainq, misfit' = misfit,
  }

  // The pane's thread writes the next bytes, or applies the next size.
  action apply = all {
    paneq.length() > 0,
    match paneq.head() {
      | Bytes(s) => all { misfit' = (misfit or s != termSize), termSize' = termSize }
      | Size(s) => all { misfit' = misfit, termSize' = s }
    },
    paneq' = paneq.tail(),
    rexSize' = rexSize, stream' = stream, mainq' = mainq, pipe' = pipe,
  }

  action step = any {
    write,
    nondet s = Sizes.oneOf()
    resize(s),
    read, relay, take, apply,
  }

  // Every byte is written at its size.
  val bytesFit = not(misfit)
  // Once everything is through, the terminal is at Rex's size.
  val settledSize = (stream == List() and mainq == List() and pipe == List() and paneq == List()) implies termSize == rexSize
  val typeOK = and {
    Sizes.contains(rexSize), Sizes.contains(termSize),
    stream.length() <= MaxQueue, pipe.length() <= MaxQueue, paneq.length() <= MaxQueue,
  }
}

// Both rules, as the code has them: every rule holds.
module fixed {
  import resize(BbInOrder = true, RenderInOrder = true).*

  // A resize, then the program's redraw for it: the redraw lands at 2.
  run redrawFitsTest =
    init
      .then(resize(2)).then(write)
      .then(read).then(read).then(take).then(take).then(apply).then(apply)
      .expect(bytesFit and termSize == 2 and settledSize)
}

// Neither rule: each of these traces breaks a rule.
module before {
  import resize(BbInOrder = false, RenderInOrder = false).*

  // The size waits on bb's main queue while the redraw goes ahead: Emacs's
  // redraw for 110x26 in a 90x20 terminal.
  run redrawOvertakesSizeTest =
    init
      .then(resize(2)).then(write)
      .then(read).then(read).then(take).then(apply)
      .expect(not(bytesFit))

  // Old bytes still queued for the pane when the main loop resizes.
  run sizeOvertakesBytesTest =
    init
      .then(write).then(resize(2))
      .then(read).then(take).then(read).then(relay).then(take).then(apply)
      .expect(not(bytesFit))
}

// Only BbInOrder: rexg-render's immediate resize overtakes queued bytes.
module bbOnly {
  import resize(BbInOrder = true, RenderInOrder = false).*

  run sizeOvertakesBytesTest =
    init
      .then(write).then(resize(2))
      .then(read).then(take).then(read).then(take).then(apply)
      .expect(not(bytesFit))
}
