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

// The usage-limit pause of bbh super, from desktop/build/bbh/src/bbh/super/model.clj:
// a session's agent.json names a limit, the controller pauses it and holds its
// mail, and a timer, the person or the Router ends the pause. Subject 0 is the
// Router, 1..NW are Workers, each working from the start with its brief in
// the outbox. One action is one command or one model/outbox-plan pass.
module limits {
  const NW: int
  const MaxRows: int
  const MaxHits: int       // limits hit in a behavior: the account recovers in the end

  type WSt = Working | Cancelling | Done | Cancelled
  // An outbox row as the controller and the mailbox hold it: Pending in the
  // outbox, InMailbox once sent (its file waits), Taken once the session took
  // the file and the row was acked, or Dropped as a dead letter.
  type RowSt = NoRow | Pending | InMailbox | Taken | Dropped
  // Brief and Msg are mail (worker-running, say!, job-exited!), Continue is
  // the [Resumed] row of :session-resumed and Cancel the :cancel row.
  type Kind = Brief | Msg | Continue | Cancel
  type Row = { st: RowSt, to: int, kind: Kind }

  type L = {
    wstate: int -> WSt,
    paused: int -> bool,       // :paused on the Router or a Worker
    interrupted: int -> bool,  // a limit stopped its turn and no [Resumed] row has reached it since
    rows: int -> Row,          // :outbox, by id o1, o2, ...
    nrows: int,
    last: int -> int,          // the newest row each session took into its transcript
    disorder: bool,            // a session took a row older than one it had taken
    staggered: bool,           // a pause ended within pause-timing's :stagger-ms (:last-resume)
    hits: int,
  }

  var l: L

  val subjects = 0.to(NW)
  val workers = 1.to(NW)
  val rowIds = 1.to(MaxRows)
  pure val noRow = { st: NoRow, to: 0, kind: Msg }

  action init = l' = {
    wstate: subjects.mapBy(_ => Working), // the Router's stays Working
    paused: subjects.mapBy(_ => false),
    interrupted: subjects.mapBy(_ => false),
    rows: rowIds.mapBy(r => if (r <= NW) { st: Pending, to: r, kind: Brief } else noRow),
    nrows: NW,
    last: subjects.mapBy(_ => 0),
    disorder: false,
    staggered: false,
    hits: 0,
  }

  // The Router always has a session; a Worker until it finishes.
  def live(c: L, s: int): bool = c.wstate.get(s).in(Set(Working, Cancelling))
  // Who takes a [Resumed] row: the Router, or a Worker still working.
  def resumable(c: L, s: int): bool = c.wstate.get(s) == Working
  def addRow(c: L, dest: int, kind: Kind): L =
    { ...c, nrows: c.nrows + 1, rows: c.rows.set(c.nrows + 1, { st: Pending, to: dest, kind: kind }) }
  // dead-letter?: a row to a Worker that finished.
  def dead(c: L, r: int): bool = c.wstate.get(c.rows.get(r).to).in(Set(Done, Cancelled))
  // paused-conversations: the hold, which a cancel passes.
  def held(c: L, r: int): bool = c.paused.get(c.rows.get(r).to) and c.rows.get(r).kind != Cancel

  // The person's message to the Router (decide* :say!, fold* :router-queued),
  // or a Worker's job exiting (decide* :job-exited!, fold* :job-exited): mail.
  action msg(s: int): bool = all {
    l.nrows < MaxRows, live(l, s),
    l' = addRow(l, s, Msg),
  }

  // decide* :limit-hit!, fold* :session-paused: observe-limits! read a newer
  // limit in agent.json. Only the Router and a working Worker pause; for any
  // other it is no event.
  action limitHit(s: int): bool = all {
    l.hits < MaxHits,
    l' = { ...(if (resumable(l, s)) { ...l, paused: l.paused.set(s, true), interrupted: l.interrupted.set(s, true) } else l),
           hits: l.hits + 1 },
  }

  // fold* :session-resumed: the pause ends and :last-resume moves; a timer or
  // the person's resume owes a live session a [Resumed] row.
  def resumed(c: L, s: int, continue: bool): L = {
    val c2 = { ...c, paused: c.paused.set(s, false), staggered: true }
    // The bound: with every row id used, the pause still ends, without the row.
    if (continue and resumable(c, s) and c.nrows < MaxRows) addRow(c2, s, Continue) else c2
  }

  // model/due, decide* :pause-ended! :how :timer: once the reset passed, one
  // pause per stagger.
  action timerFires(s: int): bool = all {
    l.paused.get(s), resumable(l, s), not(l.staggered),
    l' = resumed(l, s, true),
  }

  // The stagger passes.
  action tick: bool = all { l.staggered, l' = { ...l, staggered: false } }

  // decide* :pause-ended! :how :now by the person (or :resume! on a paused
  // Worker), and :how :cancel, which only lifts the hold: the person took over.
  action resumeNow(s: int): bool = all {
    l.paused.get(s),
    l' = resumed(l, s, true),
  }
  action cancelPause(s: int): bool = all {
    l.paused.get(s),
    l' = { ...resumed(l, s, false), interrupted: l.interrupted.set(s, false) },
  }

  // model/outbox-plan, one pass: dead letters dropped, the hold kept, every
  // other pending row sent (the Router's together, as they are all mail).
  action dispatch: bool = {
    val rows2 = rowIds.mapBy(r => {
      val o = l.rows.get(r)
      if (o.st.in(Set(Pending, InMailbox)) and dead(l, r)) { ...o, st: Dropped }
      else if (o.st == Pending and not(held(l, r))) { ...o, st: InMailbox }
      else o
    })
    all { rows2 != l.rows, l' = { ...l, rows: rows2 } }
  }

  // A session that took its cancel (session/receive-control!) takes no more mail.
  def cancelled(c: L, s: int): bool =
    rowIds.exists(r => c.rows.get(r).to == s and c.rows.get(r).kind == Cancel and c.rows.get(r).st == Taken)

  // The live session takes the files in its mailbox, then outbox-plan acks
  // them: a cancel first, which session/receive-control! acts on outside the
  // transcript, else all its mail, oldest first, in one turn (session/take-mail!
  // and record!), which a [Resumed] row among it continues.
  action take(s: int): bool = {
    val box = rowIds.filter(r => l.rows.get(r).st == InMailbox and l.rows.get(r).to == s)
    val cancels = box.filter(r => l.rows.get(r).kind == Cancel)
    val mine = if (cancels != Set()) cancels else if (cancelled(l, s)) Set() else box
    val mail = mine.filter(r => l.rows.get(r).kind != Cancel)
    all {
      mine != Set(), live(l, s),
      l' = { ...l,
             rows: rowIds.mapBy(r => if (mine.contains(r)) { ...l.rows.get(r), st: Taken } else l.rows.get(r)),
             last: if (mail == Set()) l.last else l.last.set(s, mail.fold(l.last.get(s), (m, r) => if (r > m) r else m)),
             disorder: l.disorder or mail.exists(r => r < l.last.get(s)),
             interrupted: if (mine.exists(r => l.rows.get(r).kind == Continue)) l.interrupted.set(s, false) else l.interrupted },
    }
  }

  // decide* :cancel!, fold* :cancel-requested
  action cancel(w: int): bool = all {
    l.wstate.get(w) == Working, l.nrows < MaxRows,
    l' = { ...addRow(l, w, Cancel), wstate: l.wstate.set(w, Cancelling) },
  }

  // decide* :cancel-delivered! from model/cancel-plan once the session took
  // the cancel; fold* :worker-finished drops the pause.
  action cancelDelivered(w: int): bool = all {
    l.wstate.get(w) == Cancelling,
    rowIds.forall(r => (l.rows.get(r).to == w and l.rows.get(r).kind == Cancel) implies l.rows.get(r).st == Taken),
    l' = { ...l, wstate: l.wstate.set(w, Cancelled), paused: l.paused.set(w, false) },
  }

  // decide* :finish-worker! :done: super-done! in a turn.
  action finish(w: int): bool = all {
    l.wstate.get(w) == Working,
    l' = { ...l, wstate: l.wstate.set(w, Done), paused: l.paused.set(w, false) },
  }

  action step = any {
    nondet s = subjects.oneOf() any { msg(s), limitHit(s), timerFires(s), resumeNow(s), cancelPause(s), take(s) },
    nondet w = workers.oneOf() any { cancel(w), cancelDelivered(w), finish(w) },
    dispatch, tick,
  }

  // Rules

  // Each session takes its mail once and oldest first: a turn takes its
  // files in row order, and no row older than one already taken comes later.
  val inOrder = not(l.disorder)
  // A [Resumed] row is made only for the Router or a Worker still working.
  temporal continueToLive = always(orKeep(rowIds.forall(r =>
    (l.rows.get(r).st == NoRow and next(l.rows.get(r).st) == Pending and next(l.rows.get(r).kind) == Continue)
      implies subjects.exists(s => next(l.rows.get(r).to) == s and l.wstate.get(s) == Working)), l))
  // A pause holds the mail: no step sends a paused session anything but a cancel.
  temporal heldWhilePaused = always(orKeep(rowIds.forall(r =>
    (l.rows.get(r).st == Pending and l.rows.get(r).kind != Cancel and l.paused.get(l.rows.get(r).to) and next(l.paused.get(l.rows.get(r).to)))
      implies next(l.rows.get(r).st) != InMailbox), l))
  // A pause fails nothing: the steps that pause or resume move no Worker.
  temporal pauseFailsNothing = always(orKeep(next(l.paused) != l.paused implies
    workers.forall(w => next(l.wstate).get(w) == l.wstate.get(w) or (next(l.wstate).get(w).in(Set(Done, Cancelled)) and not(next(l.paused).get(w)))), l))

  // The controller passes and the sessions take their mail; the person may
  // never resume or cancel a pause.
  temporal plumbing = and {
    weakFair(dispatch, l),
    subjects.forall(s => weakFair(take(s), l)),
    workers.forall(w => weakFair(cancelDelivered(w), l)),
  }
  temporal clock = and { weakFair(tick, l), subjects.forall(s => weakFair(timerFires(s), l)) }
  // A cancel ends even while a limit pauses the Worker and no timer fires.
  temporal cancelEnds = plumbing implies workers.forall(w =>
    always(l.wstate.get(w) == Cancelling implies eventually(l.wstate.get(w) == Cancelled)))
  // Every pause of a live session ends, and every message to it arrives.
  temporal pauseEnds = (plumbing and clock) implies subjects.forall(s =>
    always(l.paused.get(s) implies eventually(not(l.paused.get(s)))))
  temporal mailArrives = (plumbing and clock) implies rowIds.forall(r =>
    always(l.rows.get(r).st == Pending implies eventually(l.rows.get(r).st.in(Set(Taken, Dropped)))))
  // The turn a limit stopped is continued: a [Resumed] row reaches the
  // session, unless the person took over, its Worker ended, or the bound left
  // no row id for it.
  temporal turnContinues = (plumbing and clock) implies subjects.forall(s =>
    always(l.interrupted.get(s) implies eventually(not(l.interrupted.get(s)) or not(live(l, s)) or l.nrows == MaxRows)))
}

// Limits.pause: the Router and one Worker, four outbox rows, two limits.
module pause {
  import limits(NW = 1, MaxRows = 4, MaxHits = 2).*

  // A limit during a tool call: the job exits while the Worker is paused,
  // and its row waits for the resume while the Router's mail goes, then
  // arrives after the brief and before the [Resumed] row.
  run toolCallLimitTest =
    init.then(dispatch).then(take(1))
      .then(limitHit(1)).then(msg(1)).then(msg(0)).then(dispatch)
      .expect(l.rows.get(2).st == Pending and l.rows.get(3).st == InMailbox)
      .then(timerFires(1)).then(dispatch).then(take(1))
      .expect(l.last.get(1) == 4 and not(l.disorder) and l.rows.get(2).st == Taken and not(l.interrupted.get(1)))

  // A limit during a cancel pauses nothing, and a cancel reaches a paused
  // Worker, whose held mail is then dropped.
  run cancelLimitTest =
    init.then(limitHit(1)).then(cancel(1)).then(limitHit(1)).then(dispatch)
      .expect(l.rows.get(2).st == InMailbox and l.rows.get(1).st == Pending)
      .then(take(1)).then(cancelDelivered(1)).then(dispatch)
      .expect(l.wstate.get(1) == Cancelled and l.rows.get(1).st == Dropped and not(l.paused.get(1)))

  // A second limit right after resuming holds the first [Resumed] row; the
  // next resume adds another, and both arrive in order after the message.
  run secondLimitTest =
    init.then(limitHit(0)).then(msg(0)).then(timerFires(0)).then(limitHit(0)).then(dispatch)
      .expect(l.rows.get(2).st == Pending and l.rows.get(3).st == Pending)
      .then(tick).then(timerFires(0)).then(dispatch).then(take(0))
      .expect(l.last.get(0) == 4 and l.rows.get(2).st == Taken and l.rows.get(3).st == Taken and not(l.paused.get(0)))
}
