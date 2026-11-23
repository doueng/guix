// -*- mode: quint; -*-
// The Worker, ask and reply lifecycles of bbh super, from
// desktop/build/bbh/src/bbh/super/model.clj. One action is one command: the
// checks in its decide* method and the fold* of the events it returns, which
// bbh.super.store commits in one transaction. Each action names them.
module super {
  const NW: int            // Workers 1..NW
  const Threads: Set[str]
  const Cap: int
  const MaxAsks: int
  const MaxReplies: int
  const Chat: bool         // model turns and health passes
  const Timers: bool       // model the :starting and :cancelling deadlines
  const Jobs: bool         // model a job nobody can identify, which holds a cancel

  // :state of a Worker; Absent before it exists.
  type WState = Absent | Queued | Starting | Working | Cancelling | Done | Failed | Cancelled
  type AskSt = NoAskSt | Open | Superseded | Sent | Dismissed | Answered
  type ReplySt = NoReplySt | RSent | RClosed | RAnswered
  // The Worker's cancellation as an outbox row.
  type CSig = NoSig | Owed | Taken
  // The Worker's jobs as reconcile! reads them: none left, one lost (:unknown
  // or :lost), or abandoned.
  type Job = NoJob | Lost | AbandonedJob

  // typed: the person typed a prompt into the Worker's session after the ask
  // opened (:prompted-at after :opened-at), which answers it.
  type Ask = { st: AskSt, worker: int, thread: str, stopped: bool, typed: bool }
  type Reply = { st: ReplySt, thread: str, picks: Set[int], to: int, taken: bool }

  type Super = {
    wstate: int -> WState,
    wthread: int -> str,     // :thread of each Worker
    launched: int -> bool,   // the Worker went through :starting, so it has or had a process
    tworker: str -> int,     // :worker of each thread
    resolved: str -> bool,   // :resolved-at of each thread is set
    asks: int -> Ask,        // :asks, by id
    replies: int -> Reply,   // :replies, by id
    ended: int -> bool,      // session-ended?: health found the Worker's session gone
    busy: int -> bool,       // the Worker's session is in a turn, as agent.json :busy says
    hbusy: int -> bool,      // :busy of the Worker's stored health, the last pass's view of busy
    fresh: int -> bool,      // a health pass ran on the Worker since the person last typed to it
    csig: int -> CSig,
    overdue: int -> WState,  // :overdue: the state a timer found it in too long, or Absent
    job: int -> Job,
    noted: int -> bool,      // :cancel-blocked is set: the person sees what holds its cancel
  }

  var s: Super

  val workers = 1.to(NW)
  val askIds = 1.to(MaxAsks)
  val replyIds = 1.to(MaxReplies)
  pure val none = 0
  pure val noThread = "none"
  pure val active = Set(Starting, Working, Cancelling)  // active-states
  pure val running = Set(Starting, Working)
  pure val finished = Set(Done, Failed, Cancelled)
  pure val noAsk = { st: NoAskSt, worker: none, thread: noThread, stopped: false, typed: false }
  pure val noReply = { st: NoReplySt, thread: noThread, picks: Set(), to: none, taken: false }

  action init = s' = {
    wstate: workers.mapBy(_ => Absent),
    wthread: workers.mapBy(_ => noThread),
    launched: workers.mapBy(_ => false),
    tworker: Threads.mapBy(_ => none),
    resolved: Threads.mapBy(_ => false),
    asks: askIds.mapBy(_ => noAsk),
    replies: replyIds.mapBy(_ => noReply),
    ended: workers.mapBy(_ => false),
    busy: workers.mapBy(_ => false),
    hbusy: workers.mapBy(_ => false),
    fresh: workers.mapBy(_ => false),
    csig: workers.mapBy(_ => NoSig),
    overdue: workers.mapBy(_ => Absent),
    job: workers.mapBy(_ => NoJob),
    noted: workers.mapBy(_ => false),
  }

  // next-id: ids are handed out in order, so Worker w exists only after w-1.
  def nextWorker(w: int): bool =
    s.wstate.get(w) == Absent and workers.forall(v => v < w implies s.wstate.get(v) != Absent)
  def nextAsk(a: int): bool =
    s.asks.get(a).st == NoAskSt and askIds.forall(b => b < a implies s.asks.get(b).st != NoAskSt)
  def nextReply(r: int): bool =
    s.replies.get(r).st == NoReplySt and replyIds.forall(q => q < r implies s.replies.get(q).st != NoReplySt)

  // thread-worker: the queued or active Worker of thread t.
  def threadWorkers(c: Super, t: str): Set[int] =
    workers.filter(w => c.wthread.get(w) == t and c.wstate.get(w).in(active.union(Set(Queued))))
  // session-ended?
  pure def ended(c: Super, w: int): bool = c.ended.get(w) and c.wstate.get(w).in(Set(Working, Cancelling))
  // holds-slot?
  def slotWorkers(c: Super): Set[int] =
    workers.filter(w => c.wstate.get(w).in(active) and not(ended(c, w)) and not(c.resolved.get(c.wthread.get(w))))
  // blocked-by
  def blocked(c: Super, w: int): bool = slotWorkers(c).exclude(Set(w)).size() >= Cap
  def openAsks(c: Super, t: str): Set[int] =
    askIds.filter(a => c.asks.get(a).st == Open and c.asks.get(a).thread == t)
  def setAsks(asks: int -> Ask, ids: Set[int], st: AskSt): int -> Ask =
    askIds.mapBy(a => if (ids.contains(a)) { ...asks.get(a), st: st } else asks.get(a))
  // Open asks of the Workers in ws, superseded.
  def supersede(c: Super, ws: Set[int]): int -> Ask =
    askIds.mapBy(a => if (ws.contains(c.asks.get(a).worker) and c.asks.get(a).st == Open)
                        { ...c.asks.get(a), st: Superseded } else c.asks.get(a))
  // fold* :worker-finished: a reply the Workers in ws have not answered is
  // closed, a state of its own. Nobody is left to act on it.
  def closeReplies(c: Super, ws: Set[int]): int -> Reply =
    replyIds.mapBy(r => if (c.replies.get(r).st == RSent and ws.contains(c.replies.get(r).to))
                          { ...c.replies.get(r), st: RClosed } else c.replies.get(r))

  // waiting?: no event stores it; the asks say it.
  def waiting(c: Super, w: int): bool =
    c.wstate.get(w) == Working
      and askIds.exists(a => c.asks.get(a).st == Open and c.asks.get(a).worker == w and c.asks.get(a).stopped)

  // decide* :start-worker!: clearing refuses a thread whose Worker's session
  // still runs; otherwise it ends that Worker, whose session ended, as failed
  // (fold* :worker-finished) and reopens an archived thread (fold*
  // :thread-reopened), then the new Worker w starts on thread t against the
  // state after that (fold* :worker-started).
  action startWorker(w: int, t: str): bool = {
    val olds = threadWorkers(s, t)
    val ws = workers.mapBy(v => if (olds.contains(v)) Failed else s.wstate.get(v))
    val res = s.resolved.set(t, false)
    val slots = workers.filter(v => ws.get(v).in(active)
                                    and not(s.ended.get(v) and ws.get(v).in(Set(Working, Cancelling)))
                                    and not(res.get(s.wthread.get(v))))
    val queue = slots.exclude(Set(w)).size() >= Cap
    all {
      nextWorker(w),
      olds.forall(v => ended(s, v)),
      s' = { ...s,
             wthread: s.wthread.set(w, t),
             resolved: res,
             asks: supersede(s, olds),
             tworker: if (olds.union(Set(none)).contains(s.tworker.get(t))) s.tworker.set(t, none) else s.tworker,
             wstate: ws.set(w, if (queue) Queued else Starting),
             launched: if (queue) s.launched else s.launched.set(w, true),
             replies: closeReplies(s, olds) },
    }
  }

  // decide* :promote-worker! (next-queued), fold* :worker-promoted
  action promoteWorker = {
    val ready = workers.filter(w => s.wstate.get(w) == Queued and not(blocked(s, w)))
    val w = ready.fold(NW + 1, (m, v) => if (v < m) v else m)
    all {
      ready != Set(),
      s' = { ...s, wstate: s.wstate.set(w, Starting), launched: s.launched.set(w, true) },
    }
  }

  // decide* :worker-running!, fold* :worker-running
  action workerRunning(w: int): bool = all {
    s.wstate.get(w) == Starting,
    s' = { ...s, wstate: s.wstate.set(w, Working), tworker: s.tworker.set(s.wthread.get(w), w) },
  }

  // fold* :worker-finished
  def workerFinished(c: Super, w: int, outcome: WState): Super = {
    val t = c.wthread.get(w)
    { ...c,
      wstate: c.wstate.set(w, outcome),
      asks: supersede(c, Set(w)),
      tworker: if (Set(w, none).contains(c.tworker.get(t))) c.tworker.set(t, none) else c.tworker,
      replies: closeReplies(c, Set(w)) }
  }

  // decide* :finish-worker!: a Worker's super-done! or super-failed!, a
  // failed launch, or the person.
  action finishWorker(w: int, outcome: WState): bool = all {
    not(s.wstate.get(w).in(Set(Absent, Cancelling).union(finished))),
    s' = workerFinished(s, w, outcome),
  }

  // decide* :cancel!, fold* :cancel-requested. A Worker that has a
  // conversation is told through an outbox row with :delivery :cancel; a
  // queued one has none.
  action cancel(w: int): bool = all {
    s.wstate.get(w).in(running.union(Set(Queued))),
    s' = { ...s, wstate: s.wstate.set(w, Cancelling),
                 csig: s.csig.set(w, if (s.launched.get(w)) Owed else NoSig) },
  }

  // decide* :cancel-delivered!, which model/cancel-plan asks for once no job
  // nobody can identify holds the cancel (blocking-jobs) and cancel-owed? is
  // false or no session lives.
  action cancelDelivered(w: int): bool = all {
    s.wstate.get(w) == Cancelling, s.csig.get(w) != Owed or s.ended.get(w), s.job.get(w) != Lost,
    s' = workerFinished(s, w, Cancelled),
  }

  // The Worker's live session interrupts on the cancel in its mailbox and
  // deletes the file; model/outbox-plan then marks the row :done (fold*
  // :outbox-acked).
  action takeCancel(w: int): bool = all {
    s.wstate.get(w) == Cancelling, s.csig.get(w) == Owed, not(s.ended.get(w)),
    s' = { ...s, csig: s.csig.set(w, Taken) },
  }

  // decide* :ask! by Worker w, fold* :ask-opened
  action ask(w: int, a: int, stopped: bool, sup: Set[int]): bool = {
    val t = s.wthread.get(w)
    all {
      nextAsk(a),
      s.wstate.get(w).in(running), s.tworker.get(t) == w,
      sup.subseteq(openAsks(s, t)),
      s' = { ...s, asks: setAsks(s.asks, sup, Superseded)
                           .set(a, { st: Open, worker: w, thread: t, stopped: stopped, typed: false }) },
    }
  }

  // fold* :reply-sent, from decide* :reply!, :approve!, :dismiss! and
  // :archive!; the Workers in cancelled end cancelled.
  def replySent(c: Super, t: str, r: int, picks: Set[int], dismiss: Set[int], cancelled: Set[int]): Super =
    { ...c,
      replies: c.replies.set(r, { st: RSent, thread: t, picks: picks, to: c.tworker.get(t), taken: false }),
      asks: setAsks(setAsks(c.asks, picks, Sent), dismiss, Dismissed),
      wstate: workers.mapBy(w => if (cancelled.contains(w)) Cancelled else c.wstate.get(w)) }

  // decide* :reply! and :approve! by the person, with the current asks-version
  action reply(t: str, r: int, picks: Set[int], dismiss: Set[int]): bool = all {
    nextReply(r), s.tworker.get(t) != none,
    picks.subseteq(openAsks(s, t)), dismiss.subseteq(openAsks(s, t)),
    s' = replySent(s, t, r, picks, dismiss, Set()),
  }

  // decide* :archive!: dismissal, the thread's queued Worker cancelled, then
  // fold* :thread-resolved. A queued Worker asked nothing.
  action archive(t: str): bool = {
    val queued = workers.filter(w => s.wthread.get(w) == t and s.wstate.get(w) == Queued)
    val c = { ...s, resolved: s.resolved.set(t, true) }
    all {
      not(s.resolved.get(t)),
      if (openAsks(s, t) != Set() and s.tworker.get(t) != none)
        { nondet r = replyIds.oneOf() all { nextReply(r), s' = replySent(c, t, r, Set(), openAsks(s, t), queued) } }
      else
        s' = { ...c, wstate: workers.mapBy(w => if (queued.contains(w)) Cancelled else s.wstate.get(w)) },
    }
  }

  // bbh acp takes the reply's mailbox file into a turn and deletes it;
  // reconcile! then marks its outbox row :done. The file is in the
  // conversation of the Worker the reply went to, so only that Worker's live
  // session takes it. The Worker learns the reply id only from it, which
  // answered assumes: decide* :answered! itself checks the thread only.
  action take(w: int, r: int): bool = all {
    s.replies.get(r).st == RSent, not(s.replies.get(r).taken),
    s.replies.get(r).to == w, s.wstate.get(w).in(running), not(ended(s, w)),
    s' = { ...s, replies: s.replies.setBy(r, p => { ...p, taken: true }) },
  }

  // decide* :answered! by a live Worker of the reply's thread, fold*
  // :reply-answered
  action answered(w: int, r: int): bool = {
    val p = s.replies.get(r)
    all {
      p.st == RSent, p.taken, s.wstate.get(w).in(active), s.wthread.get(w) == p.thread,
      s' = { ...s, replies: s.replies.set(r, { ...p, st: RAnswered }),
                   asks: setAsks(s.asks, p.picks, Answered) },
    }
  }

  // reconcile! on an active Worker: health sees no live session, and decide*
  // :health! with fold* :health-observed stores it. session-ended? reads that
  // process, not the classification, so a later pass that names a lost job
  // or a missing wf run keeps the session ended; only :resume! clears it.
  action sessionEnds(w: int): bool = all {
    s.wstate.get(w).in(Set(Working, Cancelling)), not(s.ended.get(w)),
    s' = { ...s, ended: s.ended.set(w, true), busy: s.busy.set(w, false), hbusy: s.hbusy.set(w, false) },
  }

  // bbh acp session/prompt: a live session starts a turn. A prompt other
  // than the mail wake [mail] is the person typing, so agent.json
  // :prompted-at moves past every open ask of the Worker.
  action turnStarts(w: int, typed: bool): bool = all {
    s.wstate.get(w) == Working, not(ended(s, w)), not(s.busy.get(w)),
    s' = { ...s,
           busy: s.busy.set(w, true),
           asks: if (typed) askIds.mapBy(a => if (s.asks.get(a).st == Open and s.asks.get(a).worker == w)
                                                { ...s.asks.get(a), typed: true } else s.asks.get(a))
                 else s.asks,
           fresh: if (typed) s.fresh.set(w, false) else s.fresh },
  }

  action turnEnds(w: int): bool = all {
    s.busy.get(w),
    s' = { ...s, busy: s.busy.set(w, false) },
  }

  // reconcile! on a live Worker: decide* :health! stores what it saw of the
  // turn, and answered-in-chat with fold* :asks-answered-in-chat supersedes
  // the asks the person typed over, so the Worker no longer waits on them.
  action health(w: int): bool = all {
    s.wstate.get(w) == Working, not(ended(s, w)),
    s' = { ...s,
           hbusy: s.hbusy.set(w, s.busy.get(w)),
           fresh: s.fresh.set(w, true),
           asks: askIds.mapBy(a => if (s.asks.get(a).st == Open and s.asks.get(a).worker == w and s.asks.get(a).typed)
                                     { ...s.asks.get(a), st: Superseded } else s.asks.get(a)) },
  }

  // decide* :resume! by the person, fold* :worker-resumed: only with a free
  // slot.
  action resumeWorker(w: int): bool = all {
    ended(s, w), s.wstate.get(w) == Working, not(blocked(s, w)),
    s' = { ...s, ended: s.ended.set(w, false) },
  }

  // decide* :timer-fired! from model/due, fold* :timer-fired: a Worker
  // :starting or :cancelling past its deadline is marked. Nothing else
  // moves, so a timer kills nothing; overdue reads the mark only while the
  // Worker is in that state.
  action timerFired(w: int): bool = all {
    s.wstate.get(w).in(Set(Starting, Cancelling)), s.overdue.get(w) != s.wstate.get(w),
    s' = { ...s, overdue: s.overdue.set(w, s.wstate.get(w)) },
  }

  // conversation-jobs reads a job of a launched Worker that can no longer be
  // identified as the process that started it (process state :unknown or
  // :lost).
  action jobLost(w: int): bool = all {
    s.launched.get(w), s.wstate.get(w).in(active), s.job.get(w) == NoJob,
    s' = { ...s, job: s.job.set(w, Lost) },
  }

  // decide* :cancel-blocked!, which model/cancel-plan asks for when such a
  // job holds the cancel; fold* :cancel-blocked. Only the shown jobs change.
  action cancelBlocked(w: int): bool = all {
    s.wstate.get(w) == Cancelling, s.job.get(w) == Lost, not(s.noted.get(w)),
    s' = { ...s, noted: s.noted.set(w, true) },
  }

  // decide* :abandon-job! by the person or the Router, fold* :job-abandoned:
  // only a job the cancel was shown to wait on.
  action abandonJob(w: int): bool = all {
    s.wstate.get(w) == Cancelling, s.job.get(w) == Lost, s.noted.get(w),
    s' = { ...s, job: s.job.set(w, AbandonedJob), noted: s.noted.set(w, false) },
  }

  // cancel-blocked: the thread shows what holds the cancel and how to abandon it.
  pure def needsPerson(c: Super, w: int): bool =
    c.wstate.get(w) == Cancelling and c.job.get(w) == Lost and c.noted.get(w)

  // overdue: the thread shows under Needs attention while this holds.
  pure def overdue(c: Super, w: int): bool = c.overdue.get(w) != Absent and c.overdue.get(w) == c.wstate.get(w)

  action step = any {
    // Commands
    { nondet w = workers.oneOf() nondet t = Threads.oneOf() startWorker(w, t) },
    promoteWorker,
    { nondet w = workers.oneOf()
      any { workerRunning(w), cancel(w), cancelDelivered(w), finishWorker(w, Done), finishWorker(w, Failed) } },
    { nondet w = workers.oneOf() nondet a = askIds.oneOf() nondet stopped = Bool.oneOf()
      nondet sup = askIds.powerset().oneOf() ask(w, a, stopped, sup) },
    { nondet t = Threads.oneOf() nondet r = replyIds.oneOf()
      nondet picks = askIds.powerset().oneOf() nondet dismiss = askIds.powerset().oneOf()
      reply(t, r, picks, dismiss) },
    { nondet t = Threads.oneOf() archive(t) },
    { nondet r = replyIds.oneOf() nondet w = workers.oneOf() answered(w, r) },
    // Sessions, chat and mailboxes
    { nondet w = workers.oneOf() any { sessionEnds(w), resumeWorker(w) } },
    all { Chat, nondet w = workers.oneOf() any { health(w), turnEnds(w), nondet typed = Bool.oneOf() turnStarts(w, typed) } },
    { nondet w = workers.oneOf() nondet r = replyIds.oneOf() take(w, r) },
    { nondet w = workers.oneOf() takeCancel(w) },
    // Deadlines and jobs
    all { Timers, nondet w = workers.oneOf() timerFired(w) },
    all { Jobs, nondet w = workers.oneOf() any { jobLost(w), cancelBlocked(w), abandonJob(w) } },
  }

  // Rules

  // The cap counts launched Workers on live threads; holds-slot? frees the
  // slot of an archived thread.
  val capRespected = slotWorkers(s).filter(w => s.launched.get(w)).size() <= Cap
  val oneWorkerPerThread = Threads.forall(t => threadWorkers(s, t).size() <= 1)
  // An open ask has a live Worker to read its answer.
  val openAskHasWorker = askIds.forall(a => s.asks.get(a).st == Open implies s.wstate.get(s.asks.get(a).worker).in(active))
  // An open stopped ask has its Worker waiting, unless a cancel is on its
  // way. That a Worker waits only while such an ask is open, and that one
  // seen in a turn is not shown waiting (shown-state), hold by the
  // definition of waiting.
  val stoppedAskWaits = askIds.forall(a => (s.asks.get(a).st == Open and s.asks.get(a).stopped) implies
    (waiting(s, s.asks.get(a).worker) or s.wstate.get(s.asks.get(a).worker) == Cancelling))
  // Once a health pass has seen that the person typed to a Worker, no ask
  // they typed over is still open, so the Worker is not left waiting on it.
  val typedAnswerCloses = askIds.forall(a =>
    (s.asks.get(a).st == Open and s.asks.get(a).typed) implies not(s.fresh.get(s.asks.get(a).worker)))
  // A reply is answered only after its session took it from the mailbox.
  val answeredAfterTaken = replyIds.forall(r => s.replies.get(r).st == RAnswered implies s.replies.get(r).taken)
  // A reply stops waiting when its Worker ends: none stays sent to a
  // finished Worker.
  val noReplyWaitsOnFinished = replyIds.forall(r =>
    s.replies.get(r).st == RSent implies not(s.wstate.get(s.replies.get(r).to).in(finished)))
  // A Worker ends cancelled only after its session took the cancellation or
  // ended.
  val cancelledAfterTold = workers.forall(w =>
    (s.wstate.get(w) == Cancelled and s.csig.get(w) == Owed) implies s.ended.get(w))
  // Only a job the person was shown is abandoned, and a cancel completes
  // only once no lost job holds it.
  val cancelledNotHeld = workers.forall(w =>
    (s.wstate.get(w) == Cancelled and s.launched.get(w)) implies s.job.get(w) != Lost)
  // Only a launched Worker is found starting too long.
  val overdueStartingLaunched = workers.forall(w => s.overdue.get(w) == Starting implies s.launched.get(w))

  // No Worker is launched for a thread the person archived; starting one
  // reopens it first.
  temporal noLaunchOnArchived = always(orKeep(workers.forall(w =>
    (s.wstate.get(w) != Starting and next(s.wstate).get(w) == Starting)
      implies not(next(s.resolved).get(next(s.wthread).get(w)))), s))
  // A deadline kills nothing: the step that marks a Worker overdue moves no
  // Worker, thread, ask or reply, and marks only a Worker in that state.
  temporal timerKillsNothing = always(orKeep(next(s.overdue) != s.overdue implies and {
    next(s.wstate) == s.wstate, next(s.tworker) == s.tworker, next(s.resolved) == s.resolved,
    next(s.asks) == s.asks, next(s.replies) == s.replies, next(s.csig) == s.csig,
    workers.forall(w => next(s.overdue).get(w) != s.overdue.get(w) implies (next(s.overdue).get(w) != Absent and next(s.overdue).get(w) == next(s.wstate).get(w))),
  }, s))

  // reconcile! runs a pass every few seconds and acts on its plan, and a live
  // session takes the cancel in its mailbox; the person may never abandon.
  temporal fairness = workers.forall(w => and {
    weakFair(cancelDelivered(w), s), weakFair(cancelBlocked(w), s), weakFair(takeCancel(w), s),
  })
  // A cancel ends, or the person is shown the job that holds it (W262 stuck
  // cancelling on an :unknown job, with nothing to say why).
  temporal cancelEnds = fairness implies workers.forall(w =>
    always(s.wstate.get(w) == Cancelling implies eventually(s.wstate.get(w).in(finished) or needsPerson(s, w))))
}

// Super.asks: the ask and reply lifecycle on one thread, across two Worker
// generations.
module asks {
  import super(NW = 2, Threads = Set("T1"), Cap = 1, MaxAsks = 2, MaxReplies = 2,
               Chat = false, Timers = false, Jobs = false).*

  // A reply is answered only once its Worker took it from the mailbox.
  run replyTakenThenAnsweredTest =
    init
      .then(startWorker(1, "T1")).then(workerRunning(1))
      .then(ask(1, 1, true, Set()))
      .expect(waiting(s, 1))
      .then(reply("T1", 1, Set(1), Set()))
      .expect(s.replies.get(1).st == RSent and not(s.replies.get(1).taken))
      .then(take(1, 1)).then(answered(1, 1))
      .expect(s.asks.get(1).st == Answered and not(waiting(s, 1)))
}

// Super.cancel: cancels and jobs nobody can identify, on two Workers of one
// thread with a cap of one, no asks, under fairness for reconcile! and the
// session taking the cancel.
module cancel {
  import super(NW = 2, Threads = Set("T1"), Cap = 1, MaxAsks = 0, MaxReplies = 0,
               Chat = false, Timers = false, Jobs = true).*

  // W262: a cancel held by a lost job shows the person that job.
  run lostJobShownTest =
    init
      .then(startWorker(1, "T1")).then(workerRunning(1))
      .then(jobLost(1)).then(cancel(1)).then(takeCancel(1))
      .then(cancelBlocked(1))
      .expect(needsPerson(s, 1))
      .then(abandonJob(1)).then(cancelDelivered(1))
      .expect(s.wstate.get(1) == Cancelled)
}

// Super.chat: the person answers in the Worker's chat: turns, typed prompts
// and health passes on one Worker with two asks and a reply.
module chat {
  import super(NW = 1, Threads = Set("T1"), Cap = 1, MaxAsks = 2, MaxReplies = 1,
               Chat = true, Timers = false, Jobs = false).*

  // Typing over a stopped ask, then a health pass, closes it.
  run typedOverAskClosesTest =
    init
      .then(startWorker(1, "T1")).then(workerRunning(1))
      .then(ask(1, 1, true, Set()))
      .then(turnStarts(1, true)).then(health(1))
      .expect(s.asks.get(1).st == Superseded and not(waiting(s, 1)))
}

// Super.timers: a Worker :starting or :cancelling too long is marked
// overdue, on two threads with a cap of one. No asks.
module timers {
  import super(NW = 2, Threads = Set("T1", "T2"), Cap = 1, MaxAsks = 0, MaxReplies = 0,
               Chat = false, Timers = true, Jobs = false).*

  run overdueStartTest =
    init
      .then(startWorker(1, "T1")).then(timerFired(1))
      .expect(overdue(s, 1))
      .then(workerRunning(1))
      .expect(not(overdue(s, 1)))
}

// Super.workers: the cap, one Worker per thread, archive. No asks.
module workers {
  import super(NW = 3, Threads = Set("T1", "T2", "T3"), Cap = 2, MaxAsks = 0, MaxReplies = 0,
               Chat = false, Timers = false, Jobs = false).*

  // A third Worker queues behind the cap, and archiving a thread frees its
  // slot.
  run capQueuesTest =
    init
      .then(startWorker(1, "T1")).then(startWorker(2, "T2")).then(startWorker(3, "T3"))
      .expect(s.wstate.get(3) == Queued)
      .then(archive("T1")).then(promoteWorker)
      .expect(s.wstate.get(3) == Starting and capRespected)
}
