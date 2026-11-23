// -*- mode: quint; -*-
// A wf run of the change workflow, from desktop/build/bbh/src/bbh/workflow.clj
// and src/bbh/workflows/change.edn. The journal is the state: fold-event
// applies each appended line. attest! is two steps, because it reads the
// journal (current), runs the phase's gates (run-gates, which runs the :check
// command and can take minutes), and only then appends the :attest line and
// the events that decide computed from what it read, under the run's lock and
// only if the run is still where it read it.
module workflow {
  const Attesters: Set[str]  // concurrent `wf attest` calls
  const Cap: int             // default-max-visits

  type Phase = Shape | Implement | Verify | Review | Finish
  type Status = Active | Done | Blocked | Abandoned
  type Outcome = Pass | Fail | Pending
  type Pc = Idle | Gates
  // The event an attest computed. Each carries the phase it names, as the
  // journal line does.
  type Event = NoEvent(Phase) | DoneEvent(Phase) | BlockEvent(Phase) | EnterEvent((Phase, int))
  // What an attest decided, and the phase and visit it read.
  type Decided = { e: Event, read: (Phase, int) }

  var status: Status
  var phase: Phase
  var visits: Phase -> int
  var pc: str -> Pc
  var decided: str -> Decided

  pure val phases = Set(Shape, Implement, Verify, Review, Finish)
  pure val start = Shape
  pure def last(p: Phase): bool = p == Finish
  pure def nextOf(p: Phase): Phase = match p {
    | Shape => Implement
    | Implement => Verify
    | Verify => Review
    | Review => Finish
    | Finish => Finish
  }
  pure def onFail(p: Phase): Phase = if (p == Review) Implement else p

  action init = all {
    status' = Active,
    phase' = start,
    visits' = phases.mapBy(p => if (p == start) 1 else 0),
    pc' = Attesters.mapBy(_ => Idle),
    decided' = Attesters.mapBy(_ => { e: NoEvent(start), read: (start, 1) }),
  }

  // enter
  def enter(p: Phase): Event =
    if (visits.get(p) + 1 > Cap) BlockEvent(p) else EnterEvent((p, visits.get(p) + 1))

  // decide: the event that follows an attest, or none.
  def decide(o: Outcome): Event = match o {
    | Pending => NoEvent(phase)
    | Pass => if (last(phase)) DoneEvent(phase) else enter(nextOf(phase))
    | Fail => enter(onFail(phase))
  }

  // attest!, first half: current, the :active check, run-gates with
  // outcome o, decide.
  action gates(a: str, o: Outcome): bool = all {
    pc.get(a) == Idle, status == Active,
    decided' = decided.set(a, { e: decide(o), read: (phase, visits.get(phase)) }),
    pc' = pc.set(a, Gates),
    status' = status, phase' = phase, visits' = visits,
  }
  action beginAttest(a: str): bool = { nondet o = Set(Pass, Fail, Pending).oneOf() gates(a, o) }

  // attest!, second half, one step because it runs under `locked`: current
  // again, then append! of the events, each folded by fold-event, or nothing
  // when the run moved.
  action endAttest(a: str): bool = all {
    pc.get(a) == Gates,
    val d = decided.get(a)
    val moved = status != Active or d.read != (phase, visits.get(phase))
    all {
      pc' = pc.set(a, Idle),
      decided' = decided,
      status' = if (moved) status else match d.e {
        | DoneEvent(_) => Done
        | BlockEvent(_) => Blocked
        | _ => status
      },
      phase' = if (moved) phase else match d.e {
        | EnterEvent(pv) => pv._1
        | _ => phase
      },
      visits' = if (moved) visits else match d.e {
        | EnterEvent(pv) => visits.set(pv._1, pv._2)
        | _ => visits
      },
    },
  }

  // abandon!, under `locked`
  action abandon = all {
    status.in(Set(Active, Blocked)),
    status' = Abandoned,
    phase' = phase, visits' = visits, pc' = pc, decided' = decided,
  }

  action step = any {
    abandon,
    nondet a = Attesters.oneOf()
    any { beginAttest(a), endAttest(a) },
  }

  // Rules

  val visitsWithinCap = phases.forall(p => visits.get(p) <= Cap)

  // A done or abandoned run stays so, and a blocked run can only be abandoned.
  temporal settled = always(orKeep(and {
    status.in(Set(Done, Abandoned)) implies next(status) == status,
    status == Blocked implies next(status).in(Set(Blocked, Abandoned)),
  }, (status, phase, visits, pc, decided)))

  // The run moves only along the workflow from the phase it is in: to the
  // phase's :next after a pass, or its :on-fail after a fail.
  temporal followsWorkflow = always(orKeep(
    next(phase) != phase implies next(phase).in(Set(nextOf(phase), onFail(phase))),
    (status, phase, visits, pc, decided)))

  // A phase's visit count never goes back, so verdict-for never matches a
  // verdict recorded for an earlier visit.
  temporal visitsGrow = always(orKeep(
    phases.forall(p => next(visits).get(p) >= visits.get(p)),
    (status, phase, visits, pc, decided)))
}

// Workflow.serial: one `wf attest` at a time.
module serial {
  import workflow(Attesters = Set("a1"), Cap = 3).*

  // A fail in review goes back to implement for a second visit.
  run reviewFailTest =
    init
      .then(gates("a1", Pass)).then(endAttest("a1"))
      .then(gates("a1", Pass)).then(endAttest("a1"))
      .then(gates("a1", Pass)).then(endAttest("a1"))
      .expect(phase == Review)
      .then(gates("a1", Fail)).then(endAttest("a1"))
      .expect(phase == Implement and visits.get(Implement) == 2)
}

// Workflow.change: two `wf attest` calls that overlap.
module change {
  import workflow(Attesters = Set("a1", "a2"), Cap = 3).*

  // Two attests read the same phase; only the first to finish moves the run.
  run staleAttestTest =
    init
      .then(gates("a1", Pass)).then(gates("a2", Pass))
      .then(endAttest("a1"))
      .then(endAttest("a2"))
      .expect(phase == Implement and visits.get(Implement) == 1 and pc.get("a2") == Idle)
}
