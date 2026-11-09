---- MODULE Super ----
\* The Worker, ask and reply lifecycles of bbh super, from
\* desktop/build/bbh/src/bbh/super/model.clj. One action is one command:
\* the checks in its decide* method and the fold* of the events it returns,
\* which bbh.super.store commits in one transaction. Each action names them.
EXTENDS Naturals, FiniteSets

CONSTANTS NW, Threads, Cap, MaxAsks, MaxReplies, Chat  \* Chat: model turns and health passes

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
  launched,  \* the Worker went through :starting, so it has or had a process
  tworker,   \* :worker of each thread
  resolved,  \* :resolved-at of each thread is set
  asks,      \* :asks, by id
  replies,   \* :replies, by id
  ended,     \* session-ended?: health found the Worker's session gone
  busy,      \* the Worker's session is in a turn, as agent.json :busy says
  hbusy,     \* :busy of the Worker's stored health, the last pass's view of busy
  fresh      \* a health pass ran on the Worker since the person last typed to it

chat == <<busy, hbusy, fresh>>
vars == <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, busy, hbusy, fresh>>

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

\* next-id: ids are handed out in order, so Worker w exists only after w-1.
NextWorker(w) == wstate[w] = "none" /\ \A v \in Workers : v < w => wstate[v] /= "none"
NextAsk(a) == asks[a].st = "none" /\ \A b \in AskIds : b < a => asks[b].st /= "none"
NextReply(r) == replies[r].st = "none" /\ \A q \in ReplyIds : q < r => replies[q].st /= "none"

\* thread-worker: the queued or active Worker of thread t.
ThreadWorkers(t) == {w \in Workers : wthread[w] = t /\ wstate[w] \in Active \cup {"queued"}}
\* session-ended?
Ended(w) == ended[w] /\ wstate[w] \in {"working", "waiting", "cancelling"}
\* holds-slot?
SlotWorkers == {w \in Workers : wstate[w] \in Active /\ ~Ended(w) /\ ~resolved[wthread[w]]}
\* blocked-by
Blocked(w) == Cardinality(SlotWorkers \ {w}) >= Cap
OpenAsks(t) == {a \in AskIds : asks[a].st = "open" /\ asks[a].thread = t}
SetAsks(as, ids, st) == [a \in AskIds |-> IF a \in ids THEN [as[a] EXCEPT !.st = st] ELSE as[a]]

\* resume: wake the thread's Worker when no stopped ask is open.
Resume(t, as, ws) ==
  LET w == tworker[t] IN
  IF /\ w /= None
     /\ ~\E a \in AskIds : as[a].st = "open" /\ as[a].thread = t /\ as[a].stopped
     /\ ws[w] /= "cancelling"
  THEN [ws EXCEPT ![w] = "working"] ELSE ws

\* decide* :start-worker!: clearing refuses a thread whose Worker's session
\* still runs; otherwise it ends that Worker, whose session ended, as failed
\* (fold* :worker-finished) and reopens an archived thread (fold*
\* :thread-reopened), then the new Worker w starts on thread t against the
\* state after that (fold* :worker-started).
StartWorker(w, t) ==
  LET olds == ThreadWorkers(t)
      ws == [v \in Workers |-> IF v \in olds THEN "failed" ELSE wstate[v]]
      res == [resolved EXCEPT ![t] = FALSE]
      slots == {v \in Workers : ws[v] \in Active /\ ~(ended[v] /\ ws[v] \in {"working", "waiting", "cancelling"})
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
  /\ UNCHANGED replies

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
  /\ UNCHANGED <<wthread, launched, resolved, replies>>

\* decide* :finish-worker!: a Worker's super-done! or super-failed!, a failed
\* launch, or the person.
FinishWorker(w, outcome) ==
  /\ wstate[w] \notin {"none", "cancelling"} \cup Finished
  /\ WorkerFinished(w, outcome)

\* decide* :cancel!, fold* :cancel-requested
Cancel(w) ==
  /\ wstate[w] \in Running \cup {"queued"}
  /\ wstate' = [wstate EXCEPT ![w] = "cancelling"]
  /\ UNCHANGED <<wthread, launched, tworker, resolved, asks, replies>>

\* decide* :cancel-delivered!
CancelDelivered(w) == wstate[w] = "cancelling" /\ WorkerFinished(w, "cancelled")

\* decide* :ask! by Worker w, fold* :ask-opened
Ask(w, a, stopped, sup) ==
  LET t == wthread[w] IN
  /\ NextAsk(a)
  /\ wstate[w] \in Running /\ tworker[t] = w
  /\ sup \subseteq OpenAsks(t)
  /\ LET as == [SetAsks(asks, sup, "superseded") EXCEPT ![a] =
                   [st |-> "open", worker |-> w, thread |-> t, stopped |-> stopped, typed |-> FALSE]]
     IN /\ asks' = as
        /\ wstate' = IF stopped THEN [wstate EXCEPT ![w] = "waiting"] ELSE Resume(t, as, wstate)
  /\ UNCHANGED <<wthread, launched, tworker, resolved, replies>>

\* fold* :reply-sent, from decide* :reply!, :approve!, :dismiss! and :archive!
ReplySent(t, r, picks, dismiss, then(_)) ==
  LET as == SetAsks(SetAsks(asks, picks, "sent"), dismiss, "dismissed") IN
  /\ replies' = [replies EXCEPT ![r] = [st |-> "sent", thread |-> t, picks |-> picks, to |-> tworker[t], taken |-> FALSE]]
  /\ asks' = as
  /\ wstate' = then(Resume(t, as, wstate))

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
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, ended, chat>>

\* decide* :answered! by a live Worker of the reply's thread, fold* :reply-answered
Answered(w, r) ==
  LET t == replies[r].thread
      as == SetAsks(asks, replies[r].picks, "answered") IN
  /\ replies[r].st /= "none" /\ replies[r].taken /\ wstate[w] \in Active /\ wthread[w] = t
  /\ replies' = [replies EXCEPT ![r].st = "answered"]
  /\ asks' = as
  /\ wstate' = Resume(t, as, wstate)
  /\ UNCHANGED <<wthread, launched, tworker, resolved>>

\* reconcile! on an active Worker: health sees no live session, and decide*
\* :health! with fold* :health-observed stores it. session-ended? reads that
\* process, not the classification, so a later pass that names a lost job or a
\* missing wf run keeps the session ended; only :resume! clears it.
SessionEnds(w) ==
  /\ wstate[w] \in {"working", "waiting", "cancelling"} /\ ~ended[w]
  /\ ended' = [ended EXCEPT ![w] = TRUE]
  /\ busy' = [busy EXCEPT ![w] = FALSE] /\ hbusy' = [hbusy EXCEPT ![w] = FALSE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, fresh>>

\* bbh acp session/prompt: a live session starts a turn. A prompt other than
\* the mail wake [mail] is the person typing, so agent.json :prompted-at moves
\* past every open ask of the Worker.
TurnStarts(w, typed) ==
  /\ wstate[w] \in {"working", "waiting"} /\ ~Ended(w) /\ ~busy[w]
  /\ busy' = [busy EXCEPT ![w] = TRUE]
  /\ IF typed
     THEN /\ asks' = [a \in AskIds |-> IF asks[a].st = "open" /\ asks[a].worker = w
                                         THEN [asks[a] EXCEPT !.typed = TRUE] ELSE asks[a]]
          /\ fresh' = [fresh EXCEPT ![w] = FALSE]
     ELSE UNCHANGED <<asks, fresh>>
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, replies, ended, hbusy>>

TurnEnds(w) ==
  /\ busy[w] /\ busy' = [busy EXCEPT ![w] = FALSE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, ended, hbusy, fresh>>

\* reconcile! on a live Worker: decide* :health! stores what it saw of the turn,
\* and answered-in-chat with fold* :asks-answered-in-chat supersedes the asks
\* the person typed over and resumes the Worker.
Health(w) ==
  LET t == wthread[w]
      as == [a \in AskIds |-> IF asks[a].st = "open" /\ asks[a].worker = w /\ asks[a].typed
                              THEN [asks[a] EXCEPT !.st = "superseded"] ELSE asks[a]] IN
  /\ wstate[w] \in {"working", "waiting"} /\ ~Ended(w)
  /\ hbusy' = [hbusy EXCEPT ![w] = busy[w]]
  /\ fresh' = [fresh EXCEPT ![w] = TRUE]
  /\ asks' = as
  /\ wstate' = Resume(t, as, wstate)
  /\ UNCHANGED <<wthread, launched, tworker, resolved, replies, ended, busy>>

\* decide* :resume! by the person, fold* :worker-resumed: only with a free slot.
ResumeWorker(w) ==
  /\ Ended(w) /\ wstate[w] \in {"working", "waiting"} /\ ~Blocked(w)
  /\ ended' = [ended EXCEPT ![w] = FALSE]
  /\ UNCHANGED <<wstate, wthread, launched, tworker, resolved, asks, replies, chat>>

Commands ==
  \/ \E w \in Workers, t \in Threads : StartWorker(w, t)
  \/ PromoteWorker
  \/ \E w \in Workers : WorkerRunning(w) \/ Cancel(w) \/ CancelDelivered(w)
                         \/ \E o \in {"done", "failed"} : FinishWorker(w, o)
  \/ \E w \in Workers, a \in AskIds, s \in BOOLEAN, sup \in SUBSET AskIds : Ask(w, a, s, sup)
  \/ \E t \in Threads, r \in ReplyIds, p \in SUBSET AskIds, d \in SUBSET AskIds : Reply(t, r, p, d)
  \/ \E t \in Threads : Archive(t)
  \/ \E r \in ReplyIds, w \in Workers : Answered(w, r)

Next ==
  \/ Commands /\ UNCHANGED <<ended, chat>>
  \/ \E w \in Workers : SessionEnds(w) \/ ResumeWorker(w)
  \/ Chat /\ \E w \in Workers : Health(w) \/ TurnEnds(w) \/ \E typed \in BOOLEAN : TurnStarts(w, typed)
  \/ \E w \in Workers, r \in ReplyIds : Take(w, r)

Spec == Init /\ [][Next]_vars

\* ---- rules ----

\* The cap counts launched Workers on live threads; holds-slot? frees the slot
\* of an archived thread.
CapRespected == Cardinality({w \in SlotWorkers : launched[w]}) <= Cap
OneWorkerPerThread == \A t \in Threads : Cardinality(ThreadWorkers(t)) <= 1
\* An open ask has a live Worker to read its answer.
OpenAskHasWorker == \A a \in AskIds : asks[a].st = "open" => wstate[asks[a].worker] \in Active
\* A Worker shows as waiting only while one of its stopped asks is open.
WaitingMeansStopped == \A w \in Workers : wstate[w] = "waiting" =>
  \E a \in AskIds : asks[a].st = "open" /\ asks[a].stopped /\ asks[a].worker = w
\* An open stopped ask keeps its Worker waiting.
StoppedAskWaits == \A a \in AskIds : asks[a].st = "open" /\ asks[a].stopped =>
  wstate[asks[a].worker] \in {"waiting", "cancelling"}
\* execution: a Worker the last health pass saw in a turn shows :working.
Shown(w) == IF wstate[w] = "waiting" /\ hbusy[w] THEN "working" ELSE wstate[w]
\* A Worker seen mid-turn is never shown as waiting.
MidTurnNotShownWaiting == \A w \in Workers : hbusy[w] => Shown(w) /= "waiting"
\* Once a health pass has seen that the person typed to a Worker, no ask they
\* typed over is still open, so the Worker is not left waiting on it.
TypedAnswerCloses == \A a \in AskIds :
  asks[a].st = "open" /\ asks[a].typed => ~fresh[asks[a].worker]
\* A reply is answered only after its session took it from the mailbox.
AnsweredAfterTaken == \A r \in ReplyIds : replies[r].st = "answered" => replies[r].taken
\* No Worker is launched for a thread the person archived; starting one reopens it first.
NoLaunchOnArchived == [][\A w \in Workers :
  (wstate[w] /= "starting" /\ wstate'[w] = "starting") => ~resolved'[wthread'[w]]]_vars
====
