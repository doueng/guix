// -*- mode: quint; -*-
// The concurrent core of rexmux (T207): the window, its bb sidecar, and the
// Rex server.
//
// Link. The window and the sidecar talk over two FIFO pipes. Each side
// numbers its messages from 1; a receiver delivers the next number only. The
// sidecar can crash and be restarted by the window's watchdog, which empties
// both pipes, starts both numberings again, and fails the requests still
// waiting, so each request ends with exactly one answer.
//
// Panes and streams. Rex owns panes (blocks). The window opens one data
// stream per visible pane; a stream connects, goes live, and can drop. A
// pane must never have two streams at once, and a closed pane none.
//
// Agents. A program in a pane reports OSC 7501 states. Rex sends each change
// as an event to the sidecar, which folds it and sends the window its
// attention list. The person presses next-blocked. A blocked agent must be in
// the window's list once the events have arrived, and pressing next-blocked
// must eventually reach every agent that stays blocked.
//
// Control connection (Control). ghostty-rex's connection to Rex is
// rexg.model's state machine: connect, connect-failed, closed and connected
// in desktop/build/ghostty-rex/src/rexg/model.clj; rexg.main only performs
// [:connect srv gen], [:conn/adopt|close|drop srv gen], [:start-local-server]
// and [:after ms ev]. Each try is an attempt with an id, and every answer
// (connected, failed, closed) names it and may arrive in any order, late, or
// for an attempt the client no longer waits for. The server can stop or drop
// the connection; the client must never be left down with nothing that will
// try again, must attach once the server answers, and must leave no socket
// open but its live one.
//
// ghostty-rex (desktop/build/ghostty-rex) runs the same sidecar in-process:
// its two pipes are a single-thread executor and the client's event queue,
// both FIFO, and a sidecar exception answers the request with an error, so it
// is this model without crash and restart, and the rules below hold for it
// too.
//
// Panes are 1..NPanes and streams 1..NStreams, 0 standing for none, so that
// the first pane is the least: Quint has no CHOOSE.
module rexmux {
  const NPanes: int
  const NStreams: int
  const MaxReq: int
  const MaxRestarts: int
  const MaxReports: int
  const MaxDrops: int
  const Control: bool    // model the control connection
  const MaxLosses: int   // how often the control connection is lost
  const MaxGen: int      // attempt ids 1..MaxGen, reused once nothing names them
  const MaxLates: int    // late answers from an attempt it no longer waits for

  type St = Idle | Working | Done | Blocked | Error
  type PaneSt = PaneOpen | PaneClosed
  type StreamSt = StreamIdle | StreamConnecting | StreamLive
  type MsgKind = Invoke | Reply | Attention
  // A request id, or the attention set.
  type Body = Req(int) | Att(Set[int])
  type Msg = { kind: MsgKind, n: int, body: Body }

  type LinkView = {
    toBB: List[Msg], toWin: List[Msg],  // the pipes
    winSent: int, bbSent: int,          // last number each side sent
    winGot: int, bbGot: int,            // last number each side delivered
    pending: Set[int],                  // the window's open requests
    answered: int -> int,               // answers per request
    nextReq: int, bbUp: bool, restarts: int,
  }
  type PaneView = {
    pane: int -> PaneSt,
    owner: int -> int,                  // each stream's pane
    sst: int -> StreamSt,
    drops: int,
  }
  type AgentView = {
    status: int -> St,                  // Rex's truth: each pane's agent state
    reports: int,
    events: List[(int, St)],            // Rex -> sidecar event queue
    bbStatus: int -> St,                // the sidecar's fold of the events
    winAtt: Set[int],                   // the attention set the window last received
    focus: int,
    seen: Set[int],
  }

  type Conn = Down | Connecting | Up
  // The out the client will perform next.
  type Try = ToConnect | ToStart | ToAfter | Nothing
  type Kind = Connected | Failed | Closed
  type Ctrl = {
    srvUp: bool,               // the Rex server answers
    local: bool,               // local-server?: the client may start it
    conn: Conn,                // :conn of the server in rexg.model
    try: Try,
    started: bool,             // this try already started the local server
    losses: int,
    gen: int,                  // :gen, the id of the model's current attempt
    live: int,                 // the attempt rexg.main adopted, or noConn
    inflight: Set[int],        // attempts the server has not answered yet
    open: Set[int],            // attempts whose socket is open in rexg.main
    answers: Set[(Kind, int)], // answers queued for the model
    lates: int,
  }

  var link: LinkView
  var panes: PaneView
  var agents: AgentView
  var ctrl: Ctrl

  val paneIds = 1.to(NPanes)
  val streamIds = 1.to(NStreams)
  val reqIds = 1.to(MaxReq)
  pure val none = 0
  pure val states = Set(Idle, Working, Done, Blocked, Error)
  // Attempt 0 is never the current one: it stands for every earlier attempt.
  val ids = 0.to(MaxGen)
  val noConn = MaxGen + 1
  pure val kinds = Set(Connected, Failed, Closed)

  pure def least(xs: Set[int]): int = xs.fold(-1, (m, x) => if (m == -1 or x < m) x else m)

  // The attention set the sidecar computes from what it knows.
  def attention(st: int -> St, sn: Set[int]): Set[int] =
    paneIds.filter(p => st.get(p).in(Set(Blocked, Error)) or (st.get(p) == Done and not(sn.contains(p))))

  action init = all {
    link' = { toBB: [], toWin: [], winSent: 0, bbSent: 0, winGot: 0, bbGot: 0,
              pending: Set(), answered: reqIds.mapBy(_ => 0), nextReq: 1, bbUp: true, restarts: 0 },
    panes' = { pane: paneIds.mapBy(_ => PaneOpen), owner: streamIds.mapBy(_ => none),
               sst: streamIds.mapBy(_ => StreamIdle), drops: 0 },
    agents' = { status: paneIds.mapBy(_ => Idle), reports: 0, events: [], bbStatus: paneIds.mapBy(_ => Idle),
                winAtt: Set(), focus: least(paneIds), seen: Set() },
    nondet up = Bool.oneOf()
    nondet loc = Bool.oneOf()
    ctrl' = if (Control)
              { srvUp: up, local: loc, conn: Down, try: ToConnect, started: false, losses: 0,
                gen: MaxGen, live: noConn, inflight: Set(), open: Set(), answers: Set(), lates: 0 }
            else
              { srvUp: true, local: false, conn: Up, try: Nothing, started: false, losses: 0,
                gen: 1, live: 1, inflight: Set(), open: Set(1), answers: Set(), lates: 0 },
  }

  // A step of the core (link, panes and agents), or of the control
  // connection; each leaves the other part unchanged.
  action core(l: LinkView, p: PaneView, a: AgentView): bool = all { link' = l, panes' = p, agents' = a, ctrl' = ctrl }
  action control(c: Ctrl): bool = all { ctrl' = c, link' = link, panes' = panes, agents' = agents }

  // Link

  // The window asks the sidecar to run a command.
  action invoke = all {
    link.bbUp, link.nextReq <= MaxReq,
    core({ ...link,
           toBB: link.toBB.append({ kind: Invoke, n: link.winSent + 1, body: Req(link.nextReq) }),
           winSent: link.winSent + 1,
           pending: link.pending.union(Set(link.nextReq)),
           nextReq: link.nextReq + 1 }, panes, agents),
  }

  // The sidecar takes the next message and replies.
  action bbReceive = all {
    link.bbUp, link.toBB != [],
    val m = link.toBB.head()
    all {
      m.n == link.bbGot + 1,
      core({ ...link, bbGot: m.n, toBB: link.toBB.tail(),
             toWin: link.toWin.append({ kind: Reply, n: link.bbSent + 1, body: m.body }),
             bbSent: link.bbSent + 1 }, panes, agents),
    },
  }

  // The window takes the next message from the sidecar.
  action winReceive = all {
    link.toWin != [],
    val m = link.toWin.head()
    val l = { ...link, winGot: m.n, toWin: link.toWin.tail() }
    all {
      m.n == link.winGot + 1,
      match m.body {
        | Req(r) => core({ ...l, answered: l.answered.setBy(r, k => k + 1), pending: l.pending.exclude(Set(r)) },
                         panes, agents)
        | Att(att) => core(l, panes, { ...agents, winAtt: att })
      },
    },
  }

  // The sidecar dies; nothing in the pipes survives.
  action crash = all {
    link.bbUp, link.restarts < MaxRestarts,
    core({ ...link, bbUp: false, toBB: [], toWin: [] }, panes, agents),
  }

  // The watchdog restarts it: both numberings start again, the window fails
  // every waiting request (one answer each), and the new sidecar reads every
  // pane's current status from Rex before it reports attention again.
  action restart = all {
    not(link.bbUp),
    core({ ...link, bbUp: true, restarts: link.restarts + 1,
           winSent: 0, bbSent: 1, winGot: 0, bbGot: 0,
           answered: reqIds.mapBy(r => if (link.pending.contains(r)) link.answered.get(r) + 1 else link.answered.get(r)),
           pending: Set(),
           toWin: [{ kind: Attention, n: 1, body: Att(attention(agents.status, agents.seen)) }] },
         panes,
         { ...agents, events: [], bbStatus: agents.status }),
  }

  // Panes and streams

  pure def active(p: PaneView, st: int): bool = p.sst.get(st).in(Set(StreamConnecting, StreamLive))
  def hasStream(p: int): bool = streamIds.exists(st => panes.owner.get(st) == p and active(panes, st))

  // The window opens a stream for an open pane that has none.
  action attach(p: int, st: int): bool = all {
    panes.pane.get(p) == PaneOpen, not(hasStream(p)), panes.sst.get(st) == StreamIdle,
    core(link, { ...panes, owner: panes.owner.set(st, p), sst: panes.sst.set(st, StreamConnecting) }, agents),
  }

  action welcome(st: int): bool = all {
    panes.sst.get(st) == StreamConnecting,
    core(link, { ...panes, sst: panes.sst.set(st, StreamLive) }, agents),
  }

  // A stream drops (server restart, network); its slot can be reused.
  action drop(st: int): bool = all {
    active(panes, st), panes.drops < MaxDrops,
    core(link, { ...panes, sst: panes.sst.set(st, StreamIdle), owner: panes.owner.set(st, none),
                           drops: panes.drops + 1 }, agents),
  }

  // A pane closes in Rex: its stream closes with it, and its agent is gone.
  action closePane(p: int): bool = all {
    panes.pane.get(p) == PaneOpen, paneIds.filter(q => panes.pane.get(q) == PaneOpen).size() > 1,
    core(link,
         { ...panes,
           pane: panes.pane.set(p, PaneClosed),
           sst: streamIds.mapBy(st => if (panes.owner.get(st) == p) StreamIdle else panes.sst.get(st)),
           owner: streamIds.mapBy(st => if (panes.owner.get(st) == p) none else panes.owner.get(st)) },
         { ...agents,
           status: agents.status.set(p, Idle),
           events: agents.events.append((p, Idle)),
           focus: if (agents.focus == p) least(paneIds.filter(q => q != p and panes.pane.get(q) == PaneOpen))
                  else agents.focus }),
  }

  // Agents

  // A program reports a new state (OSC 7501); Rex queues the event.
  action report(p: int, st: St): bool = all {
    panes.pane.get(p) == PaneOpen, agents.reports < MaxReports, st != agents.status.get(p),
    core(link, panes,
         { ...agents,
           status: agents.status.set(p, st),
           events: agents.events.append((p, st)),
           reports: agents.reports + 1,
           seen: if (st == Done) agents.seen.exclude(Set(p)) else agents.seen }),
  }

  // The sidecar folds the next event and sends the window its attention set.
  action foldEvent = all {
    link.bbUp, agents.events != [],
    val e = agents.events.head()
    val bs = agents.bbStatus.set(e._1, e._2)
    core({ ...link,
           toWin: link.toWin.append({ kind: Attention, n: link.bbSent + 1, body: Att(attention(bs, agents.seen)) }),
           bbSent: link.bbSent + 1 },
         panes,
         { ...agents, bbStatus: bs, events: agents.events.tail() }),
  }

  // The person presses next-blocked: focus the next blocked pane the window
  // knows of, after the focused one, wrapping around.
  def blockedKnown: Set[int] =
    agents.winAtt.filter(p => agents.bbStatus.get(p) == Blocked and panes.pane.get(p) == PaneOpen)
  action nextBlocked = all {
    blockedKnown != Set(),
    nondet p = blockedKnown.oneOf()
    all {
      p != agents.focus or blockedKnown == Set(p),
      core(link, panes, { ...agents, focus: p, seen: agents.seen.union(Set(p)) }),
    },
  }

  // Control connection

  // model/failed, after connect-failed or closed: start the local server once
  // per try and try again at once, else wait model/retry-ms and try again.
  pure def failed(c: Ctrl): Ctrl =
    if (c.local and not(c.started)) { ...c, conn: Down, try: ToStart, started: true }
    else { ...c, conn: Down, try: ToAfter, started: false }

  pure def named(c: Ctrl, g: int): bool = kinds.exists(k => c.answers.contains((k, g)))
  pure def take(c: Ctrl, a: (Kind, int)): Ctrl = { ...c, answers: c.answers.exclude(Set(a)) }
  pure def current(c: Ctrl, g: int, k: Conn): bool = g == c.gen and c.conn == k

  // A socket rexg.main closes reports its own close, as a server's does.
  pure def close(c: Ctrl, socks: Set[int]): Ctrl =
    { ...c,
      open: c.open.exclude(socks),
      answers: c.answers.union(socks.intersect(c.open).map(g => (Closed, g))) }

  val nextId = if (ctrl.gen == MaxGen) 1 else ctrl.gen + 1
  // An id is reused only once nothing names it.
  def fresh(g: int): bool =
    not(ctrl.inflight.contains(g)) and not(ctrl.open.contains(g)) and g != ctrl.live and not(named(ctrl, g))

  // model/connect: a new attempt; rexg.main performs [:connect srv gen].
  action tryConnect = all {
    ctrl.try == ToConnect, ctrl.conn == Down, fresh(nextId),
    control({ ...ctrl, gen: nextId, conn: Connecting, try: Nothing, inflight: ctrl.inflight.union(Set(nextId)) }),
  }

  // The server answers attempt g: a socket and [:connected srv g c], or
  // [:connect-failed srv g why].
  action answer(g: int): bool = all {
    ctrl.inflight.contains(g),
    val c = { ...ctrl, inflight: ctrl.inflight.exclude(Set(g)) }
    control(if (ctrl.srvUp) { ...c, open: c.open.union(Set(g)), answers: c.answers.union(Set((Connected, g))) }
            else { ...c, answers: c.answers.union(Set((Failed, g))) }),
  }

  // An attempt the model no longer waits for connects too: two tries
  // succeeded, or a retry raced a slow one.
  action late = all {
    ctrl.srvUp, ctrl.lates < MaxLates, not(ctrl.open.contains(0)),
    control({ ...ctrl, open: ctrl.open.union(Set(0)), answers: ctrl.answers.union(Set((Connected, 0))),
                       lates: ctrl.lates + 1 }),
  }

  // model/connected: the current attempt is adopted, any other closed.
  action connected(g: int): bool = all {
    ctrl.answers.contains((Connected, g)),
    val c = take(ctrl, (Connected, g))
    control(if (current(ctrl, g, Connecting)) { ...c, conn: Up, live: g, started: false } else close(c, Set(g))),
  }

  // model/connect-failed: only the current attempt's counts.
  action connectFailed(g: int): bool = all {
    ctrl.answers.contains((Failed, g)),
    val c = take(ctrl, (Failed, g))
    control(if (current(ctrl, g, Connecting)) failed(c) else c),
  }

  // model/closed: the live connection's close drops it ([:conn/drop]); the
  // current try's, seen before its [:connected], fails the try; any other
  // changes nothing.
  action closed(g: int): bool = all {
    ctrl.answers.contains((Closed, g)),
    val c = take(ctrl, (Closed, g))
    control(if (current(ctrl, g, Up) or current(ctrl, g, Connecting)) { ...failed(c), live: noConn } else c),
  }

  action deliver = { nondet g = ids.oneOf() any { connected(g), connectFailed(g), closed(g) } }

  // [:start-local-server srv]: rex server start, which may fail; then a try.
  action startLocal(ok: bool): bool = all {
    ctrl.try == ToStart,
    control({ ...ctrl, srvUp: ctrl.srvUp or ok, try: ToConnect }),
  }

  // [:after ms [:connect srv]] fires.
  action timer = all { ctrl.try == ToAfter, control({ ...ctrl, try: ToConnect }) }

  // The network drops the live connection; the server may stop with it.
  action hangup = all {
    ctrl.open.contains(ctrl.live), ctrl.losses < MaxLosses,
    nondet dies = Bool.oneOf()
    control({ ...close(ctrl, if (dies) ctrl.open else Set(ctrl.live)), srvUp: not(dies), losses: ctrl.losses + 1 }),
  }

  // The server stops, closing every socket it has, or someone starts it.
  action serverStops = all {
    ctrl.srvUp, ctrl.losses < MaxLosses,
    control({ ...close(ctrl, ctrl.open), srvUp: false, losses: ctrl.losses + 1 }),
  }
  action serverStarts = all { not(ctrl.srvUp), control({ ...ctrl, srvUp: true }) }

  action step = any {
    invoke, bbReceive, winReceive, crash, restart,
    { nondet p = paneIds.oneOf() nondet st = streamIds.oneOf() attach(p, st) },
    { nondet st = streamIds.oneOf() any { welcome(st), drop(st) } },
    { nondet p = paneIds.oneOf() closePane(p) },
    { nondet p = paneIds.oneOf() nondet st = states.oneOf() report(p, st) },
    foldEvent, nextBlocked,
    all {
      Control,
      any {
        tryConnect, { nondet g = ids.oneOf() answer(g) }, late, deliver,
        startLocal(true), startLocal(false), timer, hangup, serverStops, serverStarts,
      },
    },
  }

  // Each part's actions leave the other part unchanged, so each is fair over
  // its own variables.
  temporal fairness = and {
    weakFair(bbReceive, (link, panes, agents)), weakFair(winReceive, (link, panes, agents)),
    weakFair(restart, (link, panes, agents)), weakFair(foldEvent, (link, panes, agents)),
    paneIds.forall(p => weakFair(streamIds.exists(st => attach(p, st)), (link, panes, agents))),
    streamIds.forall(st => weakFair(welcome(st), (link, panes, agents))),
    strongFair(nextBlocked, (link, panes, agents)),
    weakFair(tryConnect, ctrl), weakFair(timer, ctrl), weakFair(startLocal(false), ctrl),
    strongFair(startLocal(true), ctrl),
    weakFair(ids.exists(g => answer(g)), ctrl), weakFair(deliver, ctrl),
  }

  // Rules

  // What the types do not say: the counters, ids and owners stay in range.
  val typeOK = and {
    link.pending.subseteq(reqIds),
    streamIds.forall(st => panes.owner.get(st).in(paneIds.union(Set(none)))),
    ctrl.losses.in(0.to(MaxLosses)), ctrl.lates.in(0.to(MaxLates)),
    ctrl.gen.in(1.to(MaxGen)), ctrl.live.in(ids.union(Set(noConn))),
    ctrl.inflight.subseteq(ids), ctrl.open.subseteq(ids), ctrl.answers.forall(a => a._2.in(ids)),
  }

  // Never two answers to one request, and an answered request is no longer
  // open.
  val noDuplicateReply = reqIds.forall(r => link.answered.get(r) <= 1)
  val answeredNotPending = link.pending.forall(r => link.answered.get(r) == 0)

  // What is in a pipe is numbered in order, starting after what was
  // delivered.
  val inOrder = and {
    link.toBB.indices().forall(i => link.toBB[i].n == link.bbGot + i + 1),
    link.toWin.indices().forall(i => link.toWin[i].n == link.winGot + i + 1),
  }

  // A pane is bound to at most one stream, and a closed pane to none.
  val oneStreamPerPane = paneIds.forall(p =>
    streamIds.filter(st => panes.owner.get(st) == p and active(panes, st)).size() <= 1)
  val closedPaneNoStream = streamIds.forall(st => active(panes, st) implies panes.pane.get(panes.owner.get(st)) == PaneOpen)
  val idleStreamUnbound = streamIds.forall(st => (panes.sst.get(st) == StreamIdle) iff (panes.owner.get(st) == none))

  // Once Rex's events and the sidecar's messages have all arrived, every
  // blocked agent is in the window's attention set.
  val settled = link.bbUp and agents.events == [] and link.toWin == []
  val blockedReachable = settled implies paneIds.filter(p => agents.status.get(p) == Blocked).subseteq(agents.winAtt)
  val sidecarAgrees = settled implies agents.bbStatus == agents.status

  // The client is never down with nothing that will try again (T210: on
  // :closed it only said so), and a try always has an answer coming.
  val neverStranded = and {
    ctrl.conn == Down implies ctrl.try != Nothing,
    ctrl.conn == Connecting implies (ctrl.inflight.contains(ctrl.gen) or named(ctrl, ctrl.gen)),
  }
  // The model is up on exactly the attempt rexg.main adopted, whose socket is
  // open or whose close is still coming (so it is never up on a dead one).
  val upIsLive = and {
    (ctrl.conn == Up) iff (ctrl.live == ctrl.gen),
    ctrl.conn == Up implies (ctrl.open.contains(ctrl.live) or ctrl.answers.contains((Closed, ctrl.live))),
  }
  // Every open socket is the live one or awaits the model's verdict: no
  // socket leaks.
  val noLeak = ctrl.open.forall(g => g == ctrl.live or ctrl.answers.contains((Connected, g)))

  // Once the server answers, the client attaches, unless it stops again.
  temporal serverUpAttaches = fairness implies
    always((ctrl.conn != Up and ctrl.srvUp) implies eventually(ctrl.conn == Up or not(ctrl.srvUp)))
  // A client that may start its local server always attaches. This leans on
  // strongFair(startLocal(true)): a start that keeps being tried eventually
  // works.
  temporal localAttaches = fairness implies
    always((ctrl.conn != Up and ctrl.local) implies eventually(ctrl.conn == Up))

  // Every request is answered.
  temporal everyRequestAnswered = fairness implies
    reqIds.forall(r => always(link.pending.contains(r) implies eventually(not(link.pending.contains(r)))))
  // Every open pane eventually streams, once streams stop dropping.
  temporal panesStream = fairness implies paneIds.forall(p =>
    eventually(always(panes.pane.get(p) == PaneOpen)) implies eventually(hasStream(p) or panes.pane.get(p) == PaneClosed))
  // An agent that stays blocked is eventually focused by next-blocked.
  temporal blockedEventuallyFocused = fairness implies paneIds.forall(p =>
    eventually(always(agents.status.get(p) == Blocked and panes.pane.get(p) == PaneOpen))
      implies always(eventually(agents.focus == p)))
}

// The sidecar link alone: three requests, two sidecar crashes.
module link {
  import rexmux(NPanes = 1, NStreams = 1, MaxReq = 3, MaxRestarts = 2, MaxReports = 0, MaxDrops = 0,
                Control = false, MaxLosses = 0, MaxGen = 1, MaxLates = 0).*

  // A crash fails the waiting request once, and its late reply never comes.
  run crashAnswersOnceTest =
    init
      .then(invoke).then(bbReceive).then(crash).then(restart)
      .expect(link.answered.get(1) == 1 and link.pending == Set())
}

// Panes and streams: three panes share three stream slots that drop.
module streams {
  import rexmux(NPanes = 3, NStreams = 3, MaxReq = 0, MaxRestarts = 0, MaxReports = 0, MaxDrops = 3,
                Control = false, MaxLosses = 0, MaxGen = 1, MaxLates = 0).*

  // Closing a pane frees its stream and moves the focus off it.
  run closeFreesStreamTest =
    init
      .then(attach(1, 1)).then(welcome(1))
      .then(closePane(1))
      .expect(panes.owner.get(1) == none and panes.sst.get(1) == StreamIdle and agents.focus == 2)
}

// Agents: two panes report states while the sidecar crashes once. Streams
// are left out (NStreams = 0); streams checks them.
module agents {
  import rexmux(NPanes = 2, NStreams = 0, MaxReq = 0, MaxRestarts = 1, MaxReports = 3, MaxDrops = 0,
                Control = false, MaxLosses = 0, MaxGen = 1, MaxLates = 0).*

  // A blocked agent reaches the window, and next-blocked focuses it.
  run blockedIsFocusedTest =
    init
      .then(report(2, Blocked)).then(foldEvent).then(winReceive)
      .expect(agents.winAtt == Set(2))
      .then(nextBlocked)
      .expect(agents.focus == 2)
}

// The control connection alone: the server stops and the connection drops
// three times, for a local server the client may start and for one it may
// not, with attempt ids reused and one late answer from an old attempt. No
// requests, pane streams or agents.
module connect {
  import rexmux(NPanes = 1, NStreams = 0, MaxReq = 0, MaxRestarts = 0, MaxReports = 0, MaxDrops = 0,
                Control = true, MaxLosses = 3, MaxGen = 2, MaxLates = 1).*

  action setServer(up: bool, loc: bool): bool = control({ ...ctrl, srvUp: up, local: loc })

  // A local client whose server is down starts it, connects and attaches.
  run startsLocalServerTest =
    init
      .then(setServer(false, true))
      .then(tryConnect)
      .then(answer(1))
      .then(connectFailed(1))
      .expect(ctrl.try == ToStart and ctrl.conn == Down)
      .then(startLocal(true))
      .then(tryConnect)
      .then(answer(2))
      .then(connected(2))
      .expect(ctrl.conn == Up and ctrl.live == 2 and neverStranded and upIsLive and noLeak)

  // T210: the live connection closes and the client tries again, not only
  // says so.
  run closeRetriesTest =
    init
      .then(setServer(true, false))
      .then(tryConnect).then(answer(1)).then(connected(1))
      .then(serverStops)
      .then(closed(1))
      .expect(ctrl.conn == Down and ctrl.try == ToAfter and ctrl.live == noConn and neverStranded)

  // A late connect from an earlier attempt is closed, never adopted.
  run lateIsClosedTest =
    init
      .then(setServer(true, false))
      .then(tryConnect).then(late).then(connected(0))
      .expect(not(ctrl.open.contains(0)) and ctrl.answers.contains((Closed, 0)) and ctrl.conn == Connecting)
}
