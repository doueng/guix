---------------------------- MODULE Rexmux ----------------------------
(* The concurrent core of rexmux (T207): the window, its bb sidecar, and the
   Rex server.

   Link. The window and the sidecar talk over two FIFO pipes. Each side
   numbers its messages from 1; a receiver delivers the next number only.
   The sidecar can crash and be restarted by the window's watchdog, which
   empties both pipes, starts both numberings again, and fails the requests
   still waiting, so each request ends with exactly one answer.

   Panes and streams. Rex owns panes (blocks). The window opens one data
   stream per visible pane; a stream connects, goes live, and can drop. A
   pane must never have two streams at once, and a closed pane none.

   Agents. A program in a pane reports OSC 7501 states. Rex sends each
   change as an event to the sidecar, which folds it and sends the window its
   attention list. The person presses next-blocked. A blocked agent must be
   in the window's list once the events have arrived, and pressing
   next-blocked must eventually reach every agent that stays blocked.

   ghostty-rex (desktop/build/ghostty-rex) runs the same sidecar in-process:
   its two pipes are a single-thread executor and the client's event queue,
   both FIFO, and a sidecar exception answers the request with an error, so
   it is this model without Crash and Restart, and the rules below hold for
   it too. *)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Panes, Streams, MaxReq, MaxRestarts, MaxReports, MaxDrops

States == {"idle", "working", "done", "blocked", "error"}
Wants == {"blocked", "error", "done"}
None == "none"

VARIABLES
  toBB, toWin,          \* pipes: sequences of messages
  winSent, bbSent,      \* last number each side sent
  winGot, bbGot,        \* last number each side delivered
  pending, answered,    \* window's open requests; answers per request
  nextReq, bbUp, restarts,
  pane, owner, sst,     \* pane state; each stream's pane and state
  drops,
  status, reports,      \* Rex's truth: each pane's agent state
  events,               \* Rex -> sidecar event queue
  bbStatus,             \* the sidecar's fold of the events
  winAtt,               \* the attention set the window last received
  focus, seen

link == <<toBB, toWin, winSent, bbSent, winGot, bbGot, pending, answered, nextReq, bbUp, restarts>>
panes == <<pane, owner, sst, drops>>
agents == <<status, reports, events, bbStatus, winAtt, focus, seen>>
vars == <<link, panes, agents>>

Msg(kind, n, body) == [kind |-> kind, n |-> n, body |-> body]

\* The attention set the sidecar computes from what it knows.
Attention(st, sn) == {p \in Panes : st[p] \in {"blocked", "error"} \/ (st[p] = "done" /\ p \notin sn)}

Init ==
  /\ toBB = <<>> /\ toWin = <<>>
  /\ winSent = 0 /\ bbSent = 0 /\ winGot = 0 /\ bbGot = 0
  /\ pending = {} /\ answered = [r \in 1..MaxReq |-> 0] /\ nextReq = 1
  /\ bbUp = TRUE /\ restarts = 0
  /\ pane = [p \in Panes |-> "open"]
  /\ owner = [s \in Streams |-> None] /\ sst = [s \in Streams |-> "idle"]
  /\ drops = 0
  /\ status = [p \in Panes |-> "idle"] /\ reports = 0
  /\ events = <<>> /\ bbStatus = [p \in Panes |-> "idle"]
  /\ winAtt = {} /\ seen = {}
  /\ focus = (CHOOSE p \in Panes : TRUE)

---------------------------------------------------------------------------
(* Link *)

\* The window asks the sidecar to run a command.
Invoke ==
  /\ bbUp /\ nextReq <= MaxReq
  /\ toBB' = Append(toBB, Msg("invoke", winSent + 1, nextReq))
  /\ winSent' = winSent + 1
  /\ pending' = pending \cup {nextReq}
  /\ nextReq' = nextReq + 1
  /\ UNCHANGED <<toWin, bbSent, winGot, bbGot, answered, bbUp, restarts, panes, agents>>

\* The sidecar takes the next message and replies.
BBReceive ==
  /\ bbUp /\ toBB # <<>>
  /\ LET m == Head(toBB) IN
       /\ m.n = bbGot + 1
       /\ bbGot' = m.n
       /\ toBB' = Tail(toBB)
       /\ toWin' = Append(toWin, Msg("reply", bbSent + 1, m.body))
       /\ bbSent' = bbSent + 1
  /\ UNCHANGED <<winSent, winGot, pending, answered, nextReq, bbUp, restarts, panes, agents>>

\* The window takes the next message from the sidecar.
WinReceive ==
  /\ toWin # <<>>
  /\ LET m == Head(toWin) IN
       /\ m.n = winGot + 1
       /\ winGot' = m.n
       /\ toWin' = Tail(toWin)
       /\ IF m.kind = "reply"
            THEN /\ answered' = [answered EXCEPT ![m.body] = @ + 1]
                 /\ pending' = pending \ {m.body}
                 /\ winAtt' = winAtt
            ELSE /\ winAtt' = m.body
                 /\ UNCHANGED <<answered, pending>>
  /\ UNCHANGED <<toBB, winSent, bbSent, bbGot, nextReq, bbUp, restarts, panes,
                 status, reports, events, bbStatus, focus, seen>>

\* The sidecar dies; nothing in the pipes survives.
Crash ==
  /\ bbUp /\ restarts < MaxRestarts
  /\ bbUp' = FALSE
  /\ toBB' = <<>> /\ toWin' = <<>>
  /\ UNCHANGED <<winSent, bbSent, winGot, bbGot, pending, answered, nextReq, restarts, panes, agents>>

\* The watchdog restarts it: both numberings start again, the window fails
\* every waiting request (one answer each), and the new sidecar reads every
\* pane's current status from Rex before it reports attention again.
Restart ==
  /\ ~bbUp
  /\ bbUp' = TRUE /\ restarts' = restarts + 1
  /\ winSent' = 0 /\ bbSent' = 1 /\ winGot' = 0 /\ bbGot' = 0
  /\ answered' = [r \in 1..MaxReq |-> IF r \in pending THEN answered[r] + 1 ELSE answered[r]]
  /\ pending' = {}
  /\ events' = <<>>
  /\ bbStatus' = status
  /\ toWin' = <<Msg("agents", 1, Attention(status, seen))>>
  /\ UNCHANGED <<toBB, nextReq, panes, status, reports, winAtt, focus, seen>>

---------------------------------------------------------------------------
(* Panes and streams *)

Active(s) == sst[s] \in {"connecting", "live"}
HasStream(p) == \E s \in Streams : owner[s] = p /\ Active(s)

\* The window opens a stream for an open pane that has none.
Attach(p, s) ==
  /\ pane[p] = "open" /\ ~HasStream(p) /\ sst[s] = "idle"
  /\ owner' = [owner EXCEPT ![s] = p] /\ sst' = [sst EXCEPT ![s] = "connecting"]
  /\ UNCHANGED <<link, pane, drops, agents>>

Welcome(s) ==
  /\ sst[s] = "connecting"
  /\ sst' = [sst EXCEPT ![s] = "live"]
  /\ UNCHANGED <<link, pane, owner, drops, agents>>

\* A stream drops (server restart, network); its slot can be reused.
Drop(s) ==
  /\ Active(s) /\ drops < MaxDrops
  /\ sst' = [sst EXCEPT ![s] = "idle"] /\ owner' = [owner EXCEPT ![s] = None]
  /\ drops' = drops + 1
  /\ UNCHANGED <<link, pane, agents>>

\* A pane closes in Rex: its stream closes with it, and its agent is gone.
ClosePane(p) ==
  /\ pane[p] = "open" /\ Cardinality({q \in Panes : pane[q] = "open"}) > 1
  /\ pane' = [pane EXCEPT ![p] = "closed"]
  /\ sst' = [s \in Streams |-> IF owner[s] = p THEN "idle" ELSE sst[s]]
  /\ owner' = [s \in Streams |-> IF owner[s] = p THEN None ELSE owner[s]]
  /\ status' = [status EXCEPT ![p] = "idle"]
  /\ events' = Append(events, <<p, "idle">>)
  /\ focus' = IF focus = p THEN CHOOSE q \in Panes : q # p /\ pane[q] = "open" ELSE focus
  /\ UNCHANGED <<link, drops, reports, bbStatus, winAtt, seen>>

---------------------------------------------------------------------------
(* Agents *)

\* A program reports a new state (OSC 7501); Rex queues the event.
Report(p, st) ==
  /\ pane[p] = "open" /\ reports < MaxReports /\ st # status[p]
  /\ status' = [status EXCEPT ![p] = st]
  /\ events' = Append(events, <<p, st>>)
  /\ reports' = reports + 1
  /\ seen' = IF st = "done" THEN seen \ {p} ELSE seen
  /\ UNCHANGED <<link, panes, bbStatus, winAtt, focus>>

\* The sidecar folds the next event and sends the window its attention set.
Fold ==
  /\ bbUp /\ events # <<>>
  /\ LET e == Head(events) IN
       /\ bbStatus' = [bbStatus EXCEPT ![e[1]] = e[2]]
       /\ toWin' = Append(toWin, Msg("agents", bbSent + 1, Attention([bbStatus EXCEPT ![e[1]] = e[2]], seen)))
  /\ bbSent' = bbSent + 1
  /\ events' = Tail(events)
  /\ UNCHANGED <<toBB, winSent, winGot, bbGot, pending, answered, nextReq, bbUp, restarts,
                 panes, status, reports, winAtt, focus, seen>>

\* The person presses next-blocked: focus the next blocked pane the window
\* knows of, after the focused one, wrapping around.
BlockedKnown == {p \in winAtt : bbStatus[p] = "blocked" /\ pane[p] = "open"}
NextBlocked ==
  /\ BlockedKnown # {}
  /\ \E p \in BlockedKnown :
       /\ p # focus \/ BlockedKnown = {p}
       /\ focus' = p
       /\ seen' = seen \cup {p}
  /\ UNCHANGED <<link, panes, status, reports, events, bbStatus, winAtt>>

---------------------------------------------------------------------------
Next ==
  \/ Invoke \/ BBReceive \/ WinReceive \/ Crash \/ Restart
  \/ \E p \in Panes, s \in Streams : Attach(p, s)
  \/ \E s \in Streams : Welcome(s) \/ Drop(s)
  \/ \E p \in Panes : ClosePane(p)
  \/ \E p \in Panes, st \in States : Report(p, st)
  \/ Fold \/ NextBlocked

Fairness ==
  /\ WF_vars(BBReceive) /\ WF_vars(WinReceive) /\ WF_vars(Restart) /\ WF_vars(Fold)
  /\ \A p \in Panes : WF_vars(\E s \in Streams : Attach(p, s))
  /\ \A s \in Streams : WF_vars(Welcome(s))
  /\ SF_vars(NextBlocked)

Spec == Init /\ [][Next]_vars /\ Fairness

---------------------------------------------------------------------------
(* Rules *)

TypeOK ==
  /\ pending \subseteq 1..MaxReq
  /\ \A s \in Streams : sst[s] \in {"idle", "connecting", "live"} /\ owner[s] \in Panes \cup {None}
  /\ \A p \in Panes : status[p] \in States /\ bbStatus[p] \in States

\* Never two answers to one request, and an answered request is no longer open.
NoDuplicateReply == \A r \in 1..MaxReq : answered[r] <= 1
AnsweredNotPending == \A r \in pending : answered[r] = 0

\* What is in a pipe is numbered in order, starting after what was delivered.
InOrder ==
  /\ \A i \in 1..Len(toBB) : toBB[i].n = bbGot + i
  /\ \A i \in 1..Len(toWin) : toWin[i].n = winGot + i

\* A pane is bound to at most one stream, and a closed pane to none.
OneStreamPerPane == \A p \in Panes : Cardinality({s \in Streams : owner[s] = p /\ Active(s)}) <= 1
ClosedPaneNoStream == \A s \in Streams : Active(s) => pane[owner[s]] = "open"
IdleStreamUnbound == \A s \in Streams : sst[s] = "idle" <=> owner[s] = None

\* Once Rex's events and the sidecar's messages have all arrived, every
\* blocked agent is in the window's attention set.
Settled == bbUp /\ events = <<>> /\ toWin = <<>>
BlockedReachable == Settled => {p \in Panes : status[p] = "blocked"} \subseteq winAtt
SidecarAgrees == Settled => bbStatus = status

\* Every request is answered.
EveryRequestAnswered == \A r \in 1..MaxReq : (r \in pending) ~> (r \notin pending)
\* Every open pane eventually streams, once streams stop dropping.
PanesStream == \A p \in Panes : <>[](pane[p] = "open") => <>(HasStream(p) \/ pane[p] = "closed")
\* An agent that stays blocked is eventually focused by next-blocked.
BlockedEventuallyFocused == \A p \in Panes : <>[](status[p] = "blocked" /\ pane[p] = "open") => []<>(focus = p)
=============================================================================
