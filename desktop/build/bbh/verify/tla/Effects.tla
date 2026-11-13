---- MODULE Effects ----
\* What one finished Worker still owes, from desktop/build/bbh/src/bbh/super.clj
\* (settle!, effect!) and src/bbh/super/model.clj (obligations, stuck-effect,
\* the effect-done! and effect-failed! commands). The controller performs the
\* first effect the Worker owes and then records it, two steps with a crash
\* possible between them. An effect looks at the world first, so performing
\* it again changes nothing, and its record has a fixed command id, so
\* recording it again adds nothing.
EXTENDS Naturals, Sequences

CONSTANTS Tries  \* model/effect-tries

Order == <<"stop-jobs", "close-wf", "advance", "retire">>  \* model/effect-order

Kinds == {Order[i] : i \in 1..Len(Order)}
Before(k) == {Order[j] : j \in {i \in 1..Len(Order) : \E n \in 1..Len(Order) : Order[n] = k /\ i < n}}

VARIABLES done,    \* kinds with an effect-done event
          tries,   \* effect-failed events per kind
          changes, \* how often each effect changed the world
          pc, cur  \* the controller: idle, or CUR ran or threw and is not recorded yet
vars == <<done, tries, changes, pc, cur>>

Init ==
  /\ done = {} /\ tries = [k \in Kinds |-> 0] /\ changes = [k \in Kinds |-> 0]
  /\ pc = "idle" /\ cur = Order[1]

Stuck(k) == k \notin done /\ tries[k] >= Tries
\* model/obligations: owed, not done, not failed too often.
Owed(k) == k \notin done /\ tries[k] < Tries
First(k) == Owed(k) /\ \A j \in Before(k) : ~Owed(j)

\* effect!: the world moves unless it already has, and the call returns or throws.
Perform(k) ==
  /\ pc = "idle" /\ First(k)
  /\ cur' = k
  /\ \/ /\ changes' = [changes EXCEPT ![k] = IF @ = 0 THEN 1 ELSE @]
        /\ pc' \in {"ran", "threw"}  \* it can throw after the world moved
     \/ /\ UNCHANGED changes /\ pc' = "threw"
  /\ UNCHANGED <<done, tries>>

\* effect-done! under effect-<worker>-<kind>, or effect-failed! under ...-fail-<n>.
Record ==
  /\ pc /= "idle" /\ pc' = "idle"
  /\ IF pc = "ran"
     THEN done' = done \cup {cur} /\ UNCHANGED tries
     ELSE tries' = [tries EXCEPT ![cur] = @ + 1] /\ UNCHANGED done
  /\ UNCHANGED <<changes, cur>>

\* The controller dies; what it had not recorded is forgotten.
Crash == pc' = "idle" /\ UNCHANGED <<done, tries, changes, cur>>

Next == Record \/ Crash \/ \E k \in Kinds : Perform(k)
Spec == Init /\ [][Next]_vars

\* However often the controller dies, no effect changes the world twice.
ChangedOnce == \A k \in Kinds : changes[k] <= 1
\* An effect recorded done was performed.
RecordedWasPerformed == \A k \in done : changes[k] = 1
TriesWithinCap == \A k \in Kinds : tries[k] <= Tries
\* An effect is done only after each one before it is done or stuck.
InOrder == \A k \in done : \A j \in Before(k) : j \in done \/ Stuck(j)
\* Records only grow: a done effect stays done.
RecordsGrow == [][done \subseteq done' /\ \A k \in Kinds : tries'[k] >= tries[k]]_vars
\* A stuck effect is the person's: the controller does not perform it again.
StuckIsLeftAlone == [][\A k \in Kinds : Stuck(k) => changes'[k] = changes[k]]_vars
====
