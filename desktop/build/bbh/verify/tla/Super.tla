---- MODULE Super ----
\* The Worker, ask and reply lifecycles of bbh super, from
\* desktop/build/bbh/src/bbh/super/model.clj. One action is one command:
\* the checks in its decide* method and the fold* of the events it returns,
\* which bbh.super.store commits in one transaction. Each action names them.
EXTENDS Naturals, FiniteSets

CONSTANTS NW, Threads, Locks, Cap, MaxAsks, MaxReplies

Workers == 1..NW
None == 0
NoThread == "none"
Active == {"starting", "working", "waiting", "cancelling"}  \* active-states
Running == {"starting", "working", "waiting"}
Finished == {"done", "failed", "cancelled"}
AskIds == 1..MaxAsks
ReplyIds == 1..MaxReplies

VARIABLES
  wstate,    \* :state of each Worker, "none" before it exists
  wthread,   \* :thread of each Worker
  wlocks,    \* :locks of each Worker
  launched,  \* the Worker went through :starting, so it has or had a process
  holder,    \* :locks of the state: lock -> Worker
  tworker,   \* :worker of each thread
  resolved,  \* :resolved-at of each thread is set
  asks,      \* :asks, by id
  replies    \* :replies, by id

vars == <<wstate, wthread, wlocks, launched, holder, tworker, resolved, asks, replies>>

NoAsk == [st |-> "none", worker |-> None, thread |-> NoThread, stopped |-> FALSE]
NoReply == [st |-> "none", thread |-> NoThread, picks |-> {}]

Init ==
  /\ wstate = [w \in Workers |-> "none"]
  /\ wthread = [w \in Workers |-> NoThread]
  /\ wlocks = [w \in Workers |-> {}]
  /\ launched = [w \in Workers |-> FALSE]
  /\ holder = [l \in Locks |-> None]
  /\ tworker = [t \in Threads |-> None]
  /\ resolved = [t \in Threads |-> FALSE]
  /\ asks = [a \in AskIds |-> NoAsk]
  /\ replies = [r \in ReplyIds |-> NoReply]

\* next-id: ids are handed out in order, so Worker w exists only after w-1.
NextWorker(w) == wstate[w] = "none" /\ \A v \in Workers : v < w => wstate[v] /= "none"
NextAsk(a) == asks[a].st = "none" /\ \A b \in AskIds : b < a => asks[b].st /= "none"
NextReply(r) == replies[r].st = "none" /\ \A q \in ReplyIds : q < r => replies[q].st /= "none"

\* thread-worker: the queued or active Worker of thread t.
ThreadWorkers(t) == {w \in Workers : wthread[w] = t /\ wstate[w] \in Active \cup {"queued"}}
ActiveWorkers == {w \in Workers : wstate[w] \in Active}
\* blocked-by
Blocked(w, ls) == \/ \E l \in ls : holder[l] \notin {None, w}
                  \/ Cardinality(ActiveWorkers \ {w}) >= Cap
OpenAsks(t) == {a \in AskIds : asks[a].st = "open" /\ asks[a].thread = t}
\* take-locks
TakeLocks(w, ls) == [l \in Locks |-> IF l \in ls THEN w ELSE holder[l]]
SetAsks(as, ids, st) == [a \in AskIds |-> IF a \in ids THEN [as[a] EXCEPT !.st = st] ELSE as[a]]

\* resume: wake the thread's Worker when no stopped ask is open.
Resume(t, as, ws) ==
  LET w == tworker[t] IN
  IF /\ w /= None
     /\ ~\E a \in AskIds : as[a].st = "open" /\ as[a].thread = t /\ as[a].stopped
     /\ ~resolved[t]
     /\ ws[w] /= "cancelling"
  THEN [ws EXCEPT ![w] = "working"] ELSE ws

\* decide* :start-worker!, fold* :worker-started
StartWorker(w, t, ls) ==
  /\ NextWorker(w) /\ ~resolved[t] /\ ThreadWorkers(t) = {}
  /\ wthread' = [wthread EXCEPT ![w] = t]
  /\ wlocks' = [wlocks EXCEPT ![w] = ls]
  /\ IF Blocked(w, ls)
     THEN /\ wstate' = [wstate EXCEPT ![w] = "queued"] /\ UNCHANGED <<holder, launched>>
     ELSE /\ wstate' = [wstate EXCEPT ![w] = "starting"] /\ holder' = TakeLocks(w, ls)
          /\ launched' = [launched EXCEPT ![w] = TRUE]
  /\ UNCHANGED <<tworker, resolved, asks, replies>>

\* decide* :attach-worker!, fold* :worker-attached
AttachWorker(w, t) ==
  /\ NextWorker(w) /\ ~resolved[t] /\ ThreadWorkers(t) = {}
  /\ wstate' = [wstate EXCEPT ![w] = "working"]
  /\ wthread' = [wthread EXCEPT ![w] = t]
  /\ tworker' = [tworker EXCEPT ![t] = w]
  /\ UNCHANGED <<wlocks, launched, holder, resolved, asks, replies>>

\* decide* :promote-worker! (next-queued), fold* :worker-promoted
PromoteWorker ==
  LET ready == {w \in Workers : wstate[w] = "queued" /\ ~Blocked(w, wlocks[w])} IN
  /\ ready /= {}
  /\ LET w == CHOOSE v \in ready : \A u \in ready : v <= u IN
     /\ wstate' = [wstate EXCEPT ![w] = "starting"]
     /\ holder' = TakeLocks(w, wlocks[w])
     /\ launched' = [launched EXCEPT ![w] = TRUE]
  /\ UNCHANGED <<wthread, wlocks, tworker, resolved, asks, replies>>

\* decide* :worker-running!, fold* :worker-running
WorkerRunning(w) ==
  /\ wstate[w] = "starting"
  /\ wstate' = [wstate EXCEPT ![w] = "working"]
  /\ holder' = TakeLocks(w, wlocks[w])
  /\ tworker' = [tworker EXCEPT ![wthread[w]] = w]
  /\ UNCHANGED <<wthread, wlocks, launched, resolved, asks, replies>>

\* fold* :worker-finished
WorkerFinished(w, outcome) ==
  LET t == wthread[w] IN
  /\ wstate' = [wstate EXCEPT ![w] = outcome]
  /\ asks' = [a \in AskIds |-> IF asks[a].worker = w /\ asks[a].st = "open"
                                THEN [asks[a] EXCEPT !.st = "superseded"] ELSE asks[a]]
  /\ holder' = [l \in Locks |-> IF holder[l] = w THEN None ELSE holder[l]]
  /\ tworker' = IF tworker[t] \in {w, None} THEN [tworker EXCEPT ![t] = None] ELSE tworker
  /\ UNCHANGED <<wthread, wlocks, launched, resolved, replies>>

\* decide* :finish-worker!: a Worker's super-done! or super-failed!, a failed
\* launch, exhausted restarts, or the person.
FinishWorker(w, outcome) ==
  /\ wstate[w] \notin {"none", "cancelling"} \cup Finished
  /\ WorkerFinished(w, outcome)

\* decide* :cancel!, fold* :cancel-requested
Cancel(w) ==
  /\ wstate[w] \in Running \cup {"queued"}
  /\ wstate' = [wstate EXCEPT ![w] = "cancelling"]
  /\ UNCHANGED <<wthread, wlocks, launched, holder, tworker, resolved, asks, replies>>

\* decide* :cancel-delivered!
CancelDelivered(w) == wstate[w] = "cancelling" /\ WorkerFinished(w, "cancelled")

\* decide* :ask! by Worker w, fold* :ask-opened
Ask(w, a, stopped, sup) ==
  LET t == wthread[w] IN
  /\ NextAsk(a)
  /\ wstate[w] \in Running /\ tworker[t] = w
  /\ sup \subseteq OpenAsks(t)
  /\ asks' = [SetAsks(asks, sup, "superseded") EXCEPT ![a] =
                 [st |-> "open", worker |-> w, thread |-> t, stopped |-> stopped]]
  /\ wstate' = IF stopped THEN [wstate EXCEPT ![w] = "waiting"] ELSE wstate
  /\ UNCHANGED <<wthread, wlocks, launched, holder, tworker, resolved, replies>>

\* fold* :reply-sent, from decide* :reply!, :approve!, :dismiss! and :archive!
ReplySent(t, r, picks, dismiss) ==
  LET as == SetAsks(SetAsks(asks, picks, "sent"), dismiss, "dismissed") IN
  /\ replies' = [replies EXCEPT ![r] = [st |-> "sent", thread |-> t, picks |-> picks]]
  /\ asks' = as
  /\ wstate' = Resume(t, as, wstate)

\* decide* :reply! and :approve! by the person, with the current asks-version
Reply(t, r, picks, dismiss) ==
  /\ NextReply(r) /\ tworker[t] /= None
  /\ picks \subseteq OpenAsks(t) /\ dismiss \subseteq OpenAsks(t)
  /\ ReplySent(t, r, picks, dismiss)
  /\ UNCHANGED <<wthread, wlocks, launched, holder, tworker, resolved>>

\* decide* :archive!: dismissal, then fold* :thread-resolved
Archive(t) ==
  /\ ~resolved[t]
  /\ resolved' = [resolved EXCEPT ![t] = TRUE]
  /\ IF OpenAsks(t) /= {} /\ tworker[t] /= None
     THEN \E r \in ReplyIds : NextReply(r) /\ ReplySent(t, r, {}, OpenAsks(t))
     ELSE UNCHANGED <<wstate, asks, replies>>
  /\ UNCHANGED <<wthread, wlocks, launched, holder, tworker>>

\* decide* :outbox-acked! on a reply's outbox, fold* :outbox-acked
Acked(r) ==
  /\ replies[r].st = "sent"
  /\ replies' = [replies EXCEPT ![r].st = "delivered"]
  /\ asks' = SetAsks(asks, {a \in replies[r].picks : asks[a].st = "sent"}, "delivered")
  /\ UNCHANGED <<wstate, wthread, wlocks, launched, holder, tworker, resolved>>

\* decide* :answered! by a live Worker of the reply's thread, fold* :reply-answered
Answered(w, r) ==
  LET t == replies[r].thread
      as == SetAsks(asks, replies[r].picks, "answered") IN
  /\ replies[r].st /= "none" /\ wstate[w] \in Active /\ wthread[w] = t
  /\ replies' = [replies EXCEPT ![r].st = "answered"]
  /\ asks' = as
  /\ wstate' = Resume(t, as, wstate)
  /\ UNCHANGED <<wthread, wlocks, launched, holder, tworker, resolved>>

Next ==
  \/ \E w \in Workers, t \in Threads : AttachWorker(w, t) \/ \E ls \in SUBSET Locks : StartWorker(w, t, ls)
  \/ PromoteWorker
  \/ \E w \in Workers : WorkerRunning(w) \/ Cancel(w) \/ CancelDelivered(w)
                         \/ \E o \in {"done", "failed"} : FinishWorker(w, o)
  \/ \E w \in Workers, a \in AskIds, s \in BOOLEAN, sup \in SUBSET AskIds : Ask(w, a, s, sup)
  \/ \E t \in Threads, r \in ReplyIds, p \in SUBSET AskIds, d \in SUBSET AskIds : Reply(t, r, p, d)
  \/ \E t \in Threads : Archive(t)
  \/ \E r \in ReplyIds : Acked(r) \/ \E w \in Workers : Answered(w, r)

Spec == Init /\ [][Next]_vars

\* ---- rules ----

\* The cap counts launched Workers; an attached conversation has no process.
CapRespected == Cardinality({w \in Workers : launched[w] /\ wstate[w] \in Active}) <= Cap
\* A launched Worker holds every lock it asked for until it finishes.
LocksHeld == \A w \in Workers : launched[w] /\ wstate[w] \in Active => \A l \in wlocks[w] : holder[l] = w
\* Nobody holds a lock for a Worker that has finished.
HolderLive == \A l \in Locks : holder[l] /= None => wstate[holder[l]] \in Active
OneWorkerPerThread == \A t \in Threads : Cardinality(ThreadWorkers(t)) <= 1
\* An open ask has a live Worker to read its answer.
OpenAskHasWorker == \A a \in AskIds : asks[a].st = "open" => wstate[asks[a].worker] \in Active
\* A Worker shows as waiting only while one of its stopped asks is open.
WaitingMeansStopped == \A w \in Workers : wstate[w] = "waiting" =>
  \E a \in AskIds : asks[a].st = "open" /\ asks[a].stopped /\ asks[a].worker = w
\* An open stopped ask keeps its Worker waiting.
StoppedAskWaits == \A a \in AskIds : asks[a].st = "open" /\ asks[a].stopped =>
  wstate[asks[a].worker] \in {"waiting", "cancelling"}
\* No Worker is launched for a thread the person archived.
NoLaunchOnArchived == [][\A w \in Workers :
  (wstate[w] /= "starting" /\ wstate'[w] = "starting") => ~resolved[wthread'[w]]]_vars
====
