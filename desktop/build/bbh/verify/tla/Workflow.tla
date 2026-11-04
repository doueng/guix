---- MODULE Workflow ----
\* A wf run of the change workflow, from desktop/build/bbh/src/bbh/workflow.clj
\* and src/bbh/workflows/change.edn. The journal is the state: fold-event
\* applies each appended line. attest! is two steps, because it reads the
\* journal (current), runs the phase's gates (run-gates, which runs the
\* :check command and can take minutes), and only then appends the :attest
\* line and the events that decide computed from what it read.
EXTENDS Naturals

CONSTANTS Attesters, Cap  \* concurrent `wf attest` calls; default-max-visits

Phases == {"shape", "implement", "verify", "review", "finish"}
Start == "shape"
NextOf == [shape |-> "implement", implement |-> "verify", verify |-> "review",
           review |-> "finish", finish |-> "none"]
OnFail == [p \in Phases |-> IF p = "review" THEN "implement" ELSE p]
Outcomes == {"pass", "fail", "pending"}

VARIABLES status, phase, visits, pc, decided  \* decided: the events an attest computed
vars == <<status, phase, visits, pc, decided>>

Init ==
  /\ status = "active" /\ phase = Start
  /\ visits = [p \in Phases |-> IF p = Start THEN 1 ELSE 0]
  /\ pc = [a \in Attesters |-> "idle"]
  /\ decided = [a \in Attesters |-> [type |-> "none", phase |-> Start, visit |-> 0]]

\* enter
Enter(st, p) ==
  IF st.visits[p] + 1 > Cap THEN [type |-> "block", phase |-> p, visit |-> 0]
  ELSE [type |-> "enter", phase |-> p, visit |-> st.visits[p] + 1]

\* decide: the event that follows an attest, or none.
Decide(st) ==
  CASE st.outcome = "pending" -> [type |-> "none", phase |-> st.phase, visit |-> 0]
    [] st.outcome = "pass" /\ NextOf[st.phase] = "none" -> [type |-> "done", phase |-> st.phase, visit |-> 0]
    [] st.outcome = "pass" -> Enter(st, NextOf[st.phase])
    [] OTHER -> Enter(st, OnFail[st.phase])

\* attest!, first half: current, the :active check, run-gates, decide.
BeginAttest(a) ==
  /\ pc[a] = "idle" /\ status = "active"
  /\ \E o \in Outcomes :
       decided' = [decided EXCEPT ![a] = Decide([phase |-> phase, visits |-> visits, outcome |-> o])]
  /\ pc' = [pc EXCEPT ![a] = "gates"]
  /\ UNCHANGED <<status, phase, visits>>

\* attest!, second half: append! of the events, each folded by fold-event.
EndAttest(a) ==
  LET e == decided[a] IN
  /\ pc[a] = "gates"
  /\ pc' = [pc EXCEPT ![a] = "idle"]
  /\ CASE e.type = "enter" -> /\ phase' = e.phase /\ visits' = [visits EXCEPT ![e.phase] = e.visit]
                               /\ UNCHANGED status
       [] e.type = "done" -> status' = "done" /\ UNCHANGED <<phase, visits>>
       [] e.type = "block" -> status' = "blocked" /\ UNCHANGED <<phase, visits>>
       [] OTHER -> UNCHANGED <<status, phase, visits>>
  /\ UNCHANGED decided

\* abandon!
Abandon ==
  /\ status \in {"active", "blocked"}
  /\ status' = "abandoned"
  /\ UNCHANGED <<phase, visits, pc, decided>>

Next == Abandon \/ \E a \in Attesters : BeginAttest(a) \/ EndAttest(a)
Spec == Init /\ [][Next]_vars

\* ---- rules ----

VisitsWithinCap == \A p \in Phases : visits[p] <= Cap
\* A done or abandoned run stays so, and a blocked run can only be abandoned.
Settled == [][/\ status \in {"done", "abandoned"} => status' = status
              /\ status = "blocked" => status' \in {"blocked", "abandoned"}]_vars
\* The run moves only along the workflow from the phase it is in: to the
\* phase's :next after a pass, or its :on-fail after a fail.
FollowsWorkflow == [][phase' /= phase => phase' \in {NextOf[phase], OnFail[phase]}]_vars
\* A phase's visit count never goes back, so verdict-for never matches a
\* verdict recorded for an earlier visit.
VisitsGrow == [][\A p \in Phases : visits'[p] >= visits[p]]_vars
====
