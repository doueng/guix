---- MODULE Super ----
\* The Worker, ask and reply lifecycles of bbh super, from
\* desktop/build/bbh/src/bbh/super/model.clj. One action is one command:
\* the checks in its decide* method and the fold* of the events it returns,
\* which bbh.super.store commits in one transaction. Each action names them.
EXTENDS Naturals, FiniteSets

CONSTANTS NW, Threads, Cap, MaxAsks, MaxReplies, Chat,  \* Chat: model turns and health passes
          Timers, \* Timers: model the :starting and :cancelling deadlines
          Jobs    \* Jobs: model a job nobody can identify, which holds a cancel

Workers == 1..NW
None == 0
NoThread == "none"
Active == {"starting", "working", "cancelling"}  \* active-states
Running == {"starting", "working"}
Finished == {"done", "failed", "cancelled"}
AskIds == 1..MaxAsks
ReplyIds == 1..MaxReplies

VARIABLES
  wstate,    \* :state of each Worker, "none" before it exists
  wthread,   \* :thread of each Worker
  launched,  \* the Worker went through :starting, so it has or had a process
  tworker,   \* :worker of each thread
  resolved,  \* :resolved-at of each thread is set
  asks,      \* :asks, by id
  replies,   \* :replies, by id
  ended,     \* session-ended?: health found the Worker's session gone
  busy,      \* the Worker's session is in a turn, as agent.json :busy says
  hbusy,     \* :busy of the Worker's stored health, the last pass's view of busy
  fresh,     \* a health pass ran on the Worker since the person last typed to it
  csig,      \* the Worker's cancellation as an outbox row: "none", "owed" or "taken"
  overdue,   \* :overdue of each Worker: the state a timer found it in too long, or "none"
  job,       \* the Worker's jobs as reconcile! reads them: "none" left, one "lost" (:unknown or :lost), or "abandoned"
  noted      \* :cancel-blocked of the Worker is set: the person sees what holds its cancel

chat == <<busy, hbusy, fresh>>
jobv == <<job, noted>>
vars == <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, busy, hbusy, fresh, csig, overdue, job, noted>>

\* typed: the person typed a prompt into the Worker's session after the ask
\* opened (:prompted-at after :opened-at), which answers it.
NoAsk == [st |-> "none", worker |-> None, thread |-> NoThread, stopped |-> FALSE, typed |-> FALSE]
NoReply == [st |-> "none", thread |-> NoThread, picks |-> {}, to |-> None, taken |-> FALSE]

Init ==
  /\ wstate = [w \in Workers |-> "none"]
  /\ wthread = [w \in Workers |-> NoThread]
  /\ launched = [w \in Workers |-> FALSE]
  /\ tworker = [t \in Threads |-> None]
  /\ resolved = [t \in Threads |-> FALSE]
  /\ asks = [a \in AskIds |-> NoAsk]
  /\ replies = [r \in ReplyIds |-> NoReply]
  /\ ended = [w \in Workers |-> FALSE]
  /\ busy = [w \in Workers |-> FALSE]
  /\ hbusy = [w \in Workers |-> FALSE]
  /\ fresh = [w \in Workers |-> FALSE]
  /\ csig = [w \in Workers |-> "none"]
  /\ overdue = [w \in Workers |-> "none"]
  /\ job = [w \in Workers |-> "none"]
  /\ noted = [w \in Workers |-> FALSE]

\* next-id: ids are handed out in order, so Worker w exists only after w-1.
NextWorker(w) == wstate[w] = "none" /\ \A v \in Workers : v < w => wstate[v] /= "none"
NextAsk(a) == asks[a].st = "none" /\ \A b \in AskIds : b < a => asks[b].st /= "none"
NextReply(r) == replies[r].st = "none" /\ \A q \in ReplyIds : q < r => replies[q].st /= "none"

\* thread-worker: the queued or active Worker of thread t.
ThreadWorkers(t) == {w \in Workers : wthread[w] = t /\ wstate[w] \in Active \cup {"queued"}}
\* session-ended?
Ended(w) == ended[w] /\ wstate[w] \in {"working", "cancelling"}
\* holds-slot?
SlotWorkers == {w \in Workers : wstate[w] \in Active /\ ~Ended(w) /\ ~resolved[wthread[w]]}
\* blocked-by
Blocked(w) == Cardinality(SlotWorkers \ {w}) >= Cap
OpenAsks(t) == {a \in AskIds : asks[a].st = "open" /\ asks[a].thread = t}
SetAsks(as, ids, st) == [a \in AskIds |-> IF a \in ids THEN [as[a] EXCEPT !.st = st] ELSE as[a]]
\* fold* :worker-finished: a reply the Workers in ws have not answered is closed, a
\* state of its own. Nobody is left to act on it.
CloseReplies(ws) == [r \in ReplyIds |-> IF replies[r].st = "sent" /\ replies[r].to \in ws
                                         THEN [replies[r] EXCEPT !.st = "closed"] ELSE replies[r]]

\* waiting?: no event stores it; the asks say it.
Waiting(w) == wstate[w] = "working" /\ \E a \in AskIds : asks[a].st = "open" /\ asks[a].worker = w /\ asks[a].stopped

\* decide* :start-worker!: clearing refuses a thread whose Worker's session
\* still runs; otherwise it ends that Worker, whose session ended, as failed
\* (fold* :worker-finished) and reopens an archived thread (fold*
\* :thread-reopened), then the new Worker w starts on thread t against the
\* state after that (fold* :worker-started).
StartWorker(w, t) ==
  LET olds == ThreadWorkers(t)
      ws == [v \in Workers |-> IF v \in olds THEN "failed" ELSE wstate[v]]
      res == [resolved EXCEPT ![t] = FALSE]
      slots == {v \in Workers : ws[v] \in Active /\ ~(ended[v] /\ ws[v] \in {"working", "cancelling"})
                                /\ ~res[wthread[v]]}
  IN
  /\ NextWorker(w)
  /\ \A v \in olds : Ended(v)
  /\ wthread' = [wthread EXCEPT ![w] = t]
  /\ resolved' = res
  /\ asks' = [a \in AskIds |-> IF asks[a].worker \in olds /\ asks[a].st = "open"
                                THEN [asks[a] EXCEPT !.st = "superseded"] ELSE asks[a]]
  /\ tworker' = IF tworker[t] \in olds \cup {None} THEN [tworker EXCEPT ![t] = None] ELSE tworker
  /\ IF Cardinality(slots \ {w}) >= Cap
     THEN /\ wstate' = [ws EXCEPT ![w] = "queued"] /\ UNCHANGED launched
     ELSE /\ wstate' = [ws EXCEPT ![w] = "starting"]
          /\ launched' = [launched EXCEPT ![w] = TRUE]
  /\ replies' = CloseReplies(olds)

\* decide* :promote-worker! (next-queued), fold* :worker-promoted
PromoteWorker ==
  LET ready == {w \in Workers : wstate[w] = "queued" /\ ~Blocked(w)} IN
  /\ ready /= {}
  /\ LET w == CHOOSE v \in ready : \A u \in ready : v <= u IN
     /\ wstate' = [wstate EXCEPT ![w] = "starting"]
     /\ launched' = [launched EXCEPT ![w] = TRUE]
  /\ UNCHANGED <<wthread, tworker, resolved, asks, replies>>

\* decide* :worker-running!, fold* :worker-running
WorkerRunning(w) ==
  /\ wstate[w] = "starting"
  /\ wstate' = [wstate EXCEPT ![w] = "working"]
  /\ tworker' = [tworker EXCEPT ![wthread[w]] = w]
  /\ UNCHANGED <<wthread, launched, resolved, asks, replies>>

\* fold* :worker-finished
WorkerFinished(w, outcome) ==
  LET t == wthread[w] IN
  /\ wstate' = [wstate EXCEPT ![w] = outcome]
  /\ asks' = [a \in AskIds |-> IF asks[a].worker = w /\ asks[a].st = "open"
                                THEN [asks[a] EXCEPT !.st = "superseded"] ELSE asks[a]]
  /\ tworker' = IF tworker[t] \in {w, None} THEN [tworker EXCEPT ![t] = None] ELSE tworker
  /\ replies' = CloseReplies({w})
  /\ UNCHANGED <<wthread, launched, resolved>>

\* decide* :finish-worker!: a Worker's super-done! or super-failed!, a failed
\* launch, or the person.
FinishWorker(w, outcome) ==
  /\ wstate[w] \notin {"none", "cancelling"} \cup Finished
  /\ WorkerFinished(w, outcome)

\* decide* :cancel!, fold* :cancel-requested. A Worker that has a conversation
\* is told through an outbox row with :delivery :cancel; a queued one has none.
Cancel(w) ==
  /\ wstate[w] \in Running \cup {"queued"}
  /\ wstate' = [wstate EXCEPT ![w] = "cancelling"]
  /\ csig' = [csig EXCEPT ![w] = IF launched[w] THEN "owed" ELSE "none"]
  /\ UNCHANGED <<wthread, launched, tworker, resolved, asks, replies>>

\* decide* :cancel-delivered!, which model/cancel-plan asks for once no job
\* nobody can identify holds the cancel (blocking-jobs) and cancel-owed? is
\* false or no session lives.
CancelDelivered(w) ==
  /\ wstate[w] = "cancelling" /\ (csig[w] /= "owed" \/ ended[w]) /\ job[w] /= "lost"
  /\ WorkerFinished(w, "cancelled")
  /\ UNCHANGED csig

\* The Worker's live session interrupts on the cancel in its mailbox and deletes
\* the file; model/outbox-plan then marks the row :done (fold* :outbox-acked).
TakeCancel(w) ==
  /\ wstate[w] = "cancelling" /\ csig[w] = "owed" /\ ~ended[w]
  /\ csig' = [csig EXCEPT ![w] = "taken"]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, chat>>

\* decide* :ask! by Worker w, fold* :ask-opened
Ask(w, a, stopped, sup) ==
  LET t == wthread[w] IN
  /\ NextAsk(a)
  /\ wstate[w] \in Running /\ tworker[t] = w
  /\ sup \subseteq OpenAsks(t)
  /\ LET as == [SetAsks(asks, sup, "superseded") EXCEPT ![a] =
                   [st |-> "open", worker |-> w, thread |-> t, stopped |-> stopped, typed |-> FALSE]]
     IN asks' = as
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, replies>>

\* fold* :reply-sent, from decide* :reply!, :approve!, :dismiss! and :archive!
ReplySent(t, r, picks, dismiss, then(_)) ==
  LET as == SetAsks(SetAsks(asks, picks, "sent"), dismiss, "dismissed") IN
  /\ replies' = [replies EXCEPT ![r] = [st |-> "sent", thread |-> t, picks |-> picks, to |-> tworker[t], taken |-> FALSE]]
  /\ asks' = as
  /\ wstate' = then(wstate)

\* decide* :reply! and :approve! by the person, with the current asks-version
Reply(t, r, picks, dismiss) ==
  /\ NextReply(r) /\ tworker[t] /= None
  /\ picks \subseteq OpenAsks(t) /\ dismiss \subseteq OpenAsks(t)
  /\ ReplySent(t, r, picks, dismiss, LAMBDA ws : ws)
  /\ UNCHANGED <<wthread, launched, tworker, resolved>>

\* decide* :archive!: dismissal, the thread's queued Worker cancelled, then
\* fold* :thread-resolved. A queued Worker asked nothing.
Archive(t) ==
  LET queued == {w \in Workers : wthread[w] = t /\ wstate[w] = "queued"}
      cancel(ws) == [w \in Workers |-> IF w \in queued THEN "cancelled" ELSE ws[w]]
  IN
  /\ ~resolved[t]
  /\ resolved' = [resolved EXCEPT ![t] = TRUE]
  /\ IF OpenAsks(t) /= {} /\ tworker[t] /= None
     THEN \E r \in ReplyIds : NextReply(r) /\ ReplySent(t, r, {}, OpenAsks(t), cancel)
     ELSE /\ wstate' = cancel(wstate) /\ UNCHANGED <<asks, replies>>
  /\ UNCHANGED <<wthread, launched, tworker>>

\* bbh acp takes the reply's mailbox file into a turn and deletes it; reconcile!
\* then marks its outbox row :done. The file is in the conversation of the
\* Worker the reply went to, so only that Worker's live session takes it. The
\* Worker learns the reply id only from it, which Answered assumes: decide*
\* :answered! itself checks the thread only.
Take(w, r) ==
  /\ replies[r].st = "sent" /\ ~replies[r].taken
  /\ replies[r].to = w /\ wstate[w] \in Running /\ ~Ended(w)
  /\ replies' = [replies EXCEPT ![r].taken = TRUE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, ended, chat, csig>>

\* decide* :answered! by a live Worker of the reply's thread, fold* :reply-answered
Answered(w, r) ==
  LET t == replies[r].thread
      as == SetAsks(asks, replies[r].picks, "answered") IN
  /\ replies[r].st = "sent" /\ replies[r].taken /\ wstate[w] \in Active /\ wthread[w] = t
  /\ replies' = [replies EXCEPT ![r].st = "answered"]
  /\ asks' = as
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved>>

\* reconcile! on an active Worker: health sees no live session, and decide*
\* :health! with fold* :health-observed stores it. session-ended? reads that
\* process, not the classification, so a later pass that names a lost job or a
\* missing wf run keeps the session ended; only :resume! clears it.
SessionEnds(w) ==
  /\ wstate[w] \in {"working", "cancelling"} /\ ~ended[w]
  /\ ended' = [ended EXCEPT ![w] = TRUE]
  /\ busy' = [busy EXCEPT ![w] = FALSE] /\ hbusy' = [hbusy EXCEPT ![w] = FALSE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, fresh, csig>>

\* bbh acp session/prompt: a live session starts a turn. A prompt other than
\* the mail wake [mail] is the person typing, so agent.json :prompted-at moves
\* past every open ask of the Worker.
TurnStarts(w, typed) ==
  /\ wstate[w] = "working" /\ ~Ended(w) /\ ~busy[w]
  /\ busy' = [busy EXCEPT ![w] = TRUE]
  /\ IF typed
     THEN /\ asks' = [a \in AskIds |-> IF asks[a].st = "open" /\ asks[a].worker = w
                                         THEN [asks[a] EXCEPT !.typed = TRUE] ELSE asks[a]]
          /\ fresh' = [fresh EXCEPT ![w] = FALSE]
     ELSE UNCHANGED <<asks, fresh>>
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, replies, ended, hbusy, csig>>

TurnEnds(w) ==
  /\ busy[w] /\ busy' = [busy EXCEPT ![w] = FALSE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, hbusy, fresh, csig>>

\* reconcile! on a live Worker: decide* :health! stores what it saw of the turn,
\* and answered-in-chat with fold* :asks-answered-in-chat supersedes the asks
\* the person typed over, so the Worker no longer waits on them.
Health(w) ==
  LET as == [a \in AskIds |-> IF asks[a].st = "open" /\ asks[a].worker = w /\ asks[a].typed
                              THEN [asks[a] EXCEPT !.st = "superseded"] ELSE asks[a]] IN
  /\ wstate[w] = "working" /\ ~Ended(w)
  /\ hbusy' = [hbusy EXCEPT ![w] = busy[w]]
  /\ fresh' = [fresh EXCEPT ![w] = TRUE]
  /\ asks' = as
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, replies, ended, busy, csig>>

\* decide* :resume! by the person, fold* :worker-resumed: only with a free slot.
ResumeWorker(w) ==
  /\ Ended(w) /\ wstate[w] = "working" /\ ~Blocked(w)
  /\ ended' = [ended EXCEPT ![w] = FALSE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, chat, csig>>

\* decide* :timer-fired! from model/due, fold* :timer-fired: a Worker :starting
\* or :cancelling past its deadline is marked. Nothing else moves, so a timer
\* kills nothing; overdue reads the mark only while the Worker is in that state.
TimerFired(w) ==
  /\ wstate[w] \in {"starting", "cancelling"} /\ overdue[w] /= wstate[w]
  /\ overdue' = [overdue EXCEPT ![w] = wstate[w]]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, chat, csig>>

\* conversation-jobs reads a job of a launched Worker that can no longer be
\* identified as the process that started it (process state :unknown or :lost).
JobLost(w) ==
  /\ launched[w] /\ wstate[w] \in Active /\ job[w] = "none"
  /\ job' = [job EXCEPT ![w] = "lost"]
  /\ UNCHANGED noted

\* decide* :cancel-blocked!, which model/cancel-plan asks for when such a job
\* holds the cancel; fold* :cancel-blocked. Only the shown jobs change.
CancelBlocked(w) ==
  /\ wstate[w] = "cancelling" /\ job[w] = "lost" /\ ~noted[w]
  /\ noted' = [noted EXCEPT ![w] = TRUE]
  /\ UNCHANGED job

\* decide* :abandon-job! by the person or the Router, fold* :job-abandoned:
\* only a job the cancel was shown to wait on.
AbandonJob(w) ==
  /\ wstate[w] = "cancelling" /\ job[w] = "lost" /\ noted[w]
  /\ job' = [job EXCEPT ![w] = "abandoned"]
  /\ noted' = [noted EXCEPT ![w] = FALSE]

\* cancel-blocked: the thread shows what holds the cancel and how to abandon it.
NeedsPerson(w) == wstate[w] = "cancelling" /\ job[w] = "lost" /\ noted[w]

\* overdue: the thread shows under Needs attention while this holds.
Overdue(w) == overdue[w] /= "none" /\ overdue[w] = wstate[w]

Commands ==
  \/ \E w \in Workers, t \in Threads : StartWorker(w, t) /\ UNCHANGED csig
  \/ PromoteWorker /\ UNCHANGED csig
  \/ \E w \in Workers : (WorkerRunning(w) /\ UNCHANGED csig) \/ Cancel(w) \/ CancelDelivered(w)
                         \/ \E o \in {"done", "failed"} : FinishWorker(w, o) /\ UNCHANGED csig
  \/ \E w \in Workers, a \in AskIds, s \in BOOLEAN, sup \in SUBSET AskIds : Ask(w, a, s, sup) /\ UNCHANGED csig
  \/ \E t \in Threads, r \in ReplyIds, p \in SUBSET AskIds, d \in SUBSET AskIds : Reply(t, r, p, d) /\ UNCHANGED csig
  \/ \E t \in Threads : Archive(t) /\ UNCHANGED csig
  \/ \E r \in ReplyIds, w \in Workers : Answered(w, r) /\ UNCHANGED csig

Moves ==
  \/ Commands /\ UNCHANGED <<ended, chat>>
  \/ \E w \in Workers : SessionEnds(w) \/ ResumeWorker(w)
  \/ Chat /\ \E w \in Workers : Health(w) \/ TurnEnds(w) \/ \E typed \in BOOLEAN : TurnStarts(w, typed)
  \/ \E w \in Workers, r \in ReplyIds : Take(w, r)
  \/ \E w \in Workers : TakeCancel(w)

JobMoves(w) == JobLost(w) \/ CancelBlocked(w) \/ AbandonJob(w)

Next ==
  \/ Moves /\ UNCHANGED <<overdue, job, noted>>
  \/ Timers /\ \E w \in Workers : TimerFired(w) /\ UNCHANGED jobv
  \/ Jobs /\ \E w \in Workers : JobMoves(w)
        /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, busy, hbusy, fresh, csig, overdue>>

Spec == Init /\ [][Next]_vars

\* reconcile! runs a pass every few seconds and acts on its plan, and a live
\* session takes the cancel in its mailbox; the person may never abandon.
FairSpec == Spec /\ \A w \in Workers :
  /\ WF_vars(CancelDelivered(w) /\ UNCHANGED <<csig, ended, chat, overdue, job, noted>>)
  /\ WF_vars(CancelBlocked(w) /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, busy, hbusy, fresh, csig, overdue, job>>)
  /\ WF_vars(TakeCancel(w) /\ UNCHANGED <<overdue, job, noted>>)

\* The cap counts launched Workers on live threads; holds-slot? frees the slot
\* of an archived thread.
CapRespected == Cardinality({w \in SlotWorkers : launched[w]}) <= Cap
OneWorkerPerThread == \A t \in Threads : Cardinality(ThreadWorkers(t)) <= 1
\* An open ask has a live Worker to read its answer.
OpenAskHasWorker == \A a \in AskIds : asks[a].st = "open" => wstate[asks[a].worker] \in Active
\* An open stopped ask has its Worker waiting, unless a cancel is on its way.
\* That a Worker waits only while such an ask is open, and that one seen in a
\* turn is not shown waiting (shown-state), hold by the definition of Waiting.
StoppedAskWaits == \A a \in AskIds : asks[a].st = "open" /\ asks[a].stopped =>
  Waiting(asks[a].worker) \/ wstate[asks[a].worker] = "cancelling"
\* Once a health pass has seen that the person typed to a Worker, no ask they
\* typed over is still open, so the Worker is not left waiting on it.
TypedAnswerCloses == \A a \in AskIds :
  asks[a].st = "open" /\ asks[a].typed => ~fresh[asks[a].worker]
\* A reply is answered only after its session took it from the mailbox.
AnsweredAfterTaken == \A r \in ReplyIds : replies[r].st = "answered" => replies[r].taken
\* A reply stops waiting when its Worker ends: none stays sent to a finished Worker.
NoReplyWaitsOnFinished == \A r \in ReplyIds : replies[r].st = "sent" => wstate[replies[r].to] \notin Finished
\* A Worker ends cancelled only after its session took the cancellation or ended.
CancelledAfterTold == \A w \in Workers : wstate[w] = "cancelled" /\ csig[w] = "owed" => ended[w]
\* No Worker is launched for a thread the person archived; starting one reopens it first.
NoLaunchOnArchived == [][\A w \in Workers :
  (wstate[w] /= "starting" /\ wstate'[w] = "starting") => ~resolved'[wthread'[w]]]_vars
\* A deadline kills nothing: the step that marks a Worker overdue moves no
\* Worker, thread, ask or reply, and marks only a Worker in that state.
TimerKillsNothing == [][overdue' /= overdue =>
  /\ UNCHANGED <<wstate, tworker, resolved, asks, replies, csig>>
  /\ \A w \in Workers : overdue'[w] /= overdue[w] => Overdue(w)']_vars
\* A cancel ends, or the person is shown the job that holds it (W262 stuck
\* cancelling on an :unknown job, with nothing to say why).
CancelEnds == \A w \in Workers : wstate[w] = "cancelling" ~> (wstate[w] \in Finished \/ NeedsPerson(w))
\* Only a job the person was shown is abandoned, and a cancel completes only
\* once no lost job holds it.
CancelledNotHeld == \A w \in Workers : wstate[w] = "cancelled" /\ launched[w] => job[w] /= "lost"
\* Only a launched Worker is found starting too long.
OverdueStartingLaunched == \A w \in Workers : overdue[w] = "starting" => launched[w]
====
