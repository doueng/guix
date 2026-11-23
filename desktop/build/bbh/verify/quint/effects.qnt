// -*- mode: quint; -*-
// What one finished Worker still owes, from desktop/build/bbh/src/bbh/super.clj
// (settle!, effect!) and src/bbh/super/model.clj (obligations, stuck-effect,
// the effect-done! and effect-failed! commands). The controller performs the
// first effect the Worker owes and then records it, two steps with a crash
// possible between them. An effect looks at the world first, so performing it
// again changes nothing, and its record has a fixed command id, so recording
// it again adds nothing.
module effects {
  const Tries: int  // model/effect-tries

  type Kind = StopJobs | CloseWf | Advance | Retire
  // The controller: idle, or cur ran or threw and is not recorded yet.
  type Pc = Idle | Ran | Threw

  var done: Set[Kind]       // kinds with an effect-done event
  var tries: Kind -> int    // effect-failed events per kind
  var changes: Kind -> int  // how often each effect changed the world
  var pc: Pc
  var cur: Kind

  pure val order = [StopJobs, CloseWf, Advance, Retire]  // model/effect-order
  pure val kinds = order.indices().map(i => order[i])
  pure def before(k: Kind): Set[Kind] =
    order.indices().filter(i => order.indices().exists(n => order[n] == k and i < n)).map(i => order[i])

  def stuck(k: Kind): bool = not(done.contains(k)) and tries.get(k) >= Tries
  // model/obligations: owed, not done, not failed too often.
  def owed(k: Kind): bool = not(done.contains(k)) and tries.get(k) < Tries
  def first(k: Kind): bool = owed(k) and before(k).forall(j => not(owed(j)))

  action init = all {
    done' = Set(), tries' = kinds.mapBy(_ => 0), changes' = kinds.mapBy(_ => 0),
    pc' = Idle, cur' = order[0],
  }

  // effect!: the world moves unless it already has, and the call returns or
  // throws (it can throw after the world moved).
  // moves: the world moved; p: the call returned (Ran) or threw.
  action performs(k: Kind, moves: bool, p: Pc): bool = all {
    pc == Idle, first(k),
    cur' = k,
    changes' = if (moves) changes.set(k, if (changes.get(k) == 0) 1 else changes.get(k)) else changes,
    pc' = p,
    done' = done, tries' = tries,
  }
  action perform(k: Kind): bool = any {
    performs(k, true, Ran), performs(k, true, Threw), performs(k, false, Threw),
  }

  // effect-done! under effect-<worker>-<kind>, or effect-failed! under
  // ...-fail-<n>.
  action record = all {
    pc != Idle, pc' = Idle,
    done' = if (pc == Ran) done.union(Set(cur)) else done,
    tries' = if (pc == Ran) tries else tries.setBy(cur, n => n + 1),
    changes' = changes, cur' = cur,
  }

  // The controller dies; what it had not recorded is forgotten.
  action crash = all { pc' = Idle, done' = done, tries' = tries, changes' = changes, cur' = cur }

  action step = any { record, crash, nondet k = kinds.oneOf() perform(k) }

  // Rules

  // However often the controller dies, no effect changes the world twice.
  val changedOnce = kinds.forall(k => changes.get(k) <= 1)
  // An effect recorded done was performed.
  val recordedWasPerformed = done.forall(k => changes.get(k) == 1)
  val triesWithinCap = kinds.forall(k => tries.get(k) <= Tries)
  // An effect is done only after each one before it is done or stuck.
  val inOrder = done.forall(k => before(k).forall(j => done.contains(j) or stuck(j)))
  // Records only grow: a done effect stays done.
  temporal recordsGrow = always(orKeep(
    done.subseteq(next(done)) and kinds.forall(k => next(tries).get(k) >= tries.get(k)),
    (done, tries, changes, pc, cur)))
  // A stuck effect is the person's: the controller does not perform it again.
  temporal stuckIsLeftAlone = always(orKeep(
    kinds.forall(k => stuck(k) implies next(changes).get(k) == changes.get(k)),
    (done, tries, changes, pc, cur)))
}

// Effects.finish: one finished Worker's four effects, each tried at most
// three times.
module finish {
  import effects(Tries = 3).*

  // A crash between performing and recording: the next try changes nothing.
  run crashThenRetryTest =
    init
      .then(performs(StopJobs, true, Ran))
      .then(crash)
      .then(performs(StopJobs, true, Threw))
      .expect(changes.get(StopJobs) == 1)

  // An effect that keeps throwing is stuck, and the next one goes first.
  run stuckEffectTest =
    init
      .then(performs(StopJobs, false, Threw)).then(record)
      .then(performs(StopJobs, false, Threw)).then(record)
      .then(performs(StopJobs, false, Threw)).then(record)
      .expect(stuck(StopJobs) and first(CloseWf))
}
