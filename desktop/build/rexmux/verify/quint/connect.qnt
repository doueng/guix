// -*- mode: quint; -*-
// The control connection of ghostty-rex to its Rex server, ported from the
// Control part of verify/tla/Rexmux.tla and checked like Rexmux.connect.cfg.
//
// rexg.model (desktop/build/ghostty-rex/src/rexg/model.clj) is a state
// machine: connect, connect-failed, closed and connected. rexg.main only
// performs [:connect srv gen], [:conn/adopt|close|drop srv gen],
// [:start-local-server] and [:after ms ev]. Each try is an attempt with an id,
// and every answer (connected, failed, closed) names it and may arrive in any
// order, late, or for an attempt the client no longer waits for. The server
// can stop or drop the connection; the client must never be left down with
// nothing that will try again, must attach once the server answers, and must
// leave no socket open but its live one.
module connect {
  const MaxLosses: int  // how often the connection is lost
  const MaxGen: int     // attempt ids 1..MaxGen, reused once nothing names them
  const MaxLates: int   // late answers from an attempt it no longer waits for

  type Conn = Down | Connecting | Up
  // The out the client will perform next.
  type Try = ToConnect | ToStart | ToAfter | Nothing
  type Kind = Connected | Failed | Closed

  type Ctrl = {
    srvUp: bool,             // the Rex server answers
    local: bool,             // local-server?: the client may start it
    conn: Conn,              // :conn of the server in rexg.model
    try: Try,
    started: bool,           // this try already started the local server
    losses: int,
    gen: int,                // :gen, the id of the model's current attempt
    live: int,               // the attempt rexg.main adopted, or noConn
    inflight: Set[int],      // attempts the server has not answered yet
    open: Set[int],          // attempts whose socket is open in rexg.main
    answers: Set[(Kind, int)], // answers queued for the model
    lates: int,
  }

  var s: Ctrl

  // Attempt 0 is never the current one: it stands for every earlier attempt.
  val ids = 0.to(MaxGen)
  val noConn = MaxGen + 1
  pure val kinds = Set(Connected, Failed, Closed)

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

  val nextId = if (s.gen == MaxGen) 1 else s.gen + 1
  // An id is reused only once nothing names it.
  def fresh(g: int): bool =
    not(s.inflight.contains(g)) and not(s.open.contains(g)) and g != s.live and not(named(s, g))

  action init = {
    nondet up = Bool.oneOf()
    nondet loc = Bool.oneOf()
    s' = { srvUp: up, local: loc, conn: Down, try: ToConnect, started: false,
           losses: 0, gen: MaxGen, live: noConn, inflight: Set(), open: Set(),
           answers: Set(), lates: 0 }
  }

  // model/connect: a new attempt; rexg.main performs [:connect srv gen].
  action tryConnect = all {
    s.try == ToConnect, s.conn == Down, fresh(nextId),
    s' = { ...s, gen: nextId, conn: Connecting, try: Nothing,
           inflight: s.inflight.union(Set(nextId)) },
  }

  // The server answers attempt g: a socket and [:connected srv g c], or
  // [:connect-failed srv g why].
  action answer(g: int): bool = all {
    s.inflight.contains(g),
    val c = { ...s, inflight: s.inflight.exclude(Set(g)) }
    s' = if (s.srvUp) { ...c, open: c.open.union(Set(g)), answers: c.answers.union(Set((Connected, g))) }
         else { ...c, answers: c.answers.union(Set((Failed, g))) },
  }

  // An attempt the model no longer waits for connects too: two tries
  // succeeded, or a retry raced a slow one.
  action late = all {
    s.srvUp, s.lates < MaxLates, not(s.open.contains(0)),
    s' = { ...s, open: s.open.union(Set(0)), answers: s.answers.union(Set((Connected, 0))),
           lates: s.lates + 1 },
  }

  // model/connected: the current attempt is adopted, any other closed.
  action connected(g: int): bool = all {
    s.answers.contains((Connected, g)),
    val c = take(s, (Connected, g))
    s' = if (current(s, g, Connecting)) { ...c, conn: Up, live: g, started: false }
         else close(c, Set(g)),
  }

  // model/connect-failed: only the current attempt's counts.
  action connectFailed(g: int): bool = all {
    s.answers.contains((Failed, g)),
    val c = take(s, (Failed, g))
    s' = if (current(s, g, Connecting)) failed(c) else c,
  }

  // model/closed: the live connection's close drops it ([:conn/drop]); the
  // current try's, seen before its [:connected], fails the try; any other
  // changes nothing.
  action closed(g: int): bool = all {
    s.answers.contains((Closed, g)),
    val c = take(s, (Closed, g))
    s' = if (current(s, g, Up) or current(s, g, Connecting)) { ...failed(c), live: noConn }
         else c,
  }

  action deliver(g: int): bool = any { connected(g), connectFailed(g), closed(g) }

  // [:start-local-server srv]: rex server start, which may fail; then a try.
  action startLocal(ok: bool): bool = all {
    s.try == ToStart,
    s' = { ...s, srvUp: s.srvUp or ok, try: ToConnect },
  }

  // [:after ms [:connect srv]] fires.
  action timer = all { s.try == ToAfter, s' = { ...s, try: ToConnect } }

  // The network drops the live connection; the server may stop with it.
  action hangup = all {
    s.open.contains(s.live), s.losses < MaxLosses,
    nondet dies = Bool.oneOf()
    s' = { ...close(s, if (dies) s.open else Set(s.live)), srvUp: not(dies), losses: s.losses + 1 },
  }

  // The server stops, closing every socket it has, or someone starts it.
  action serverStops = all {
    s.srvUp, s.losses < MaxLosses,
    s' = { ...close(s, s.open), srvUp: false, losses: s.losses + 1 },
  }
  action serverStarts = all { not(s.srvUp), s' = { ...s, srvUp: true } }

  action step = any {
    tryConnect,
    { nondet g = ids.oneOf() answer(g) },
    late,
    { nondet g = ids.oneOf() deliver(g) },
    startLocal(true), startLocal(false), timer, hangup, serverStops, serverStarts,
  }

  // Rules

  // What the types do not say: the counters and ids stay in range.
  val inRange = and {
    s.losses.in(0.to(MaxLosses)), s.lates.in(0.to(MaxLates)),
    s.gen.in(1.to(MaxGen)), s.live.in(ids.union(Set(noConn))),
    s.inflight.subseteq(ids), s.open.subseteq(ids), s.answers.forall(a => a._2.in(ids)),
  }

  // The client is never down with nothing that will try again (T210: on
  // :closed it only said so), and a try always has an answer coming.
  val neverStranded = and {
    s.conn == Down implies s.try != Nothing,
    s.conn == Connecting implies (s.inflight.contains(s.gen) or named(s, s.gen)),
  }

  // The model is up on exactly the attempt rexg.main adopted, whose socket is
  // open or whose close is still coming (so it is never up on a dead one).
  val upIsLive = and {
    (s.conn == Up) iff (s.live == s.gen),
    s.conn == Up implies (s.open.contains(s.live) or s.answers.contains((Closed, s.live))),
  }

  // Every open socket is the live one or awaits the model's verdict.
  val noLeak = s.open.forall(g => g == s.live or s.answers.contains((Connected, g)))

  val safe = inRange and neverStranded and upIsLive and noLeak

  // Liveness, under the fairness of Rexmux.tla: the client keeps acting, and a
  // local start that keeps being tried eventually works.
  temporal fairness = and {
    weakFair(tryConnect, s), weakFair(timer, s), weakFair(startLocal(false), s),
    strongFair(startLocal(true), s),
    weakFair(ids.exists(g => answer(g)), s), weakFair(ids.exists(g => deliver(g)), s),
  }
  // Once the server answers, the client attaches, unless it stops again.
  temporal serverUpAttaches =
    fairness implies always((s.conn != Up and s.srvUp) implies eventually(s.conn == Up or not(s.srvUp)))
  // A client that may start its local server always attaches.
  temporal localAttaches =
    fairness implies always((s.conn != Up and s.local) implies eventually(s.conn == Up))
}

// Rexmux.connect.cfg: the server stops and the connection drops three times,
// attempt ids are reused, and one answer comes late from an old attempt.
module connect3 {
  import connect(MaxLosses = 3, MaxGen = 2, MaxLates = 1).*

  // A local client whose server is down starts it, connects and attaches.
  run startsLocalServerTest =
    init
      .then(s' = { ...s, srvUp: false, local: true })
      .then(tryConnect)
      .then(answer(1))
      .then(connectFailed(1))
      .expect(s.try == ToStart and s.conn == Down)
      .then(startLocal(true))
      .then(tryConnect)
      .then(answer(2))
      .then(connected(2))
      .expect(s.conn == Up and s.live == 2 and safe)

  // T210: the live connection closes and the client tries again, not only
  // says so.
  run closeRetriesTest =
    init
      .then(s' = { ...s, srvUp: true, local: false })
      .then(tryConnect).then(answer(1)).then(connected(1))
      .then(serverStops)
      .then(closed(1))
      .expect(s.conn == Down and s.try == ToAfter and s.live == noConn and safe)

  // A late connect from an earlier attempt is closed, never adopted.
  run lateIsClosedTest =
    init
      .then(s' = { ...s, srvUp: true, local: false })
      .then(tryConnect).then(late).then(connected(0))
      .expect(not(s.open.contains(0)) and s.answers.contains((Closed, 0)) and s.conn == Connecting)
}
