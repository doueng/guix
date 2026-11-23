// -*- mode: quint; -*-
// A fixture for quint.core's tests: a counter that may reset, a flag and a
// sum type, with one rule of each kind that holds and one that breaks.
module counter {
  const Max: int
  type Mode = Idle | Busy(int)
  var n: int
  var mode: Mode
  var seen: Set[int]

  action init = all { n' = 0, mode' = Idle, seen' = Set(0) }
  action inc = all { n < Max, n' = n + 1, mode' = Busy(n + 1), seen' = seen.union(Set(n + 1)) }
  action reset = all { n' = 0, mode' = Idle, seen' = seen }
  action step = any { inc, reset }

  val small = n <= Max
  val tiny = n < 2
  temporal neverDown = always(orKeep(next(n) >= n, (n, mode, seen)))
  temporal grows = always(orKeep(next(seen).subseteq(seen) or seen.subseteq(next(seen)), (n, mode, seen)))
  temporal fair = weakFair(inc, (n, mode, seen))
  temporal reachesMax = fair implies eventually(n == Max)
  temporal leavesZero = fair implies always(n == 0 implies eventually(n > 0))
}

module counter3 {
  import counter(Max = 3).*
  run incTest = init.then(inc).then(inc).expect(n == 2 and mode == Busy(2))
  run wrongTest = init.then(inc).expect(n == 2)
}
