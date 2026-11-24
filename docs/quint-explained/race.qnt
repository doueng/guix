// The smallest race there is: two workers each add 1 to a shared counter.
// Explained step by step in /home/engstrand/guix/docs/quint-explained.html
module race {
  type Pc = Read | Write | Done

  var x: int            // the shared counter
  var pc: str -> Pc     // where each worker is
  var tmp: str -> int   // what each worker read

  val workers = Set("A", "B")

  action init = all {
    x' = 0,
    pc' = workers.mapBy(w => Read),
    tmp' = workers.mapBy(w => 0),
  }

  // A worker reads the counter into its own memory.
  action read(w: str): bool = all {
    pc.get(w) == Read,
    tmp' = tmp.set(w, x),
    pc' = pc.set(w, Write),
    x' = x,
  }

  // A worker writes back what it read, plus 1.
  action write(w: str): bool = all {
    pc.get(w) == Write,
    x' = tmp.get(w) + 1,
    pc' = pc.set(w, Done),
    tmp' = tmp,
  }

  // Both are done: nothing changes any more.
  action finished = all {
    workers.forall(w => pc.get(w) == Done),
    x' = x, pc' = pc, tmp' = tmp,
  }

  action step = any {
    { nondet w = workers.oneOf() any { read(w), write(w) } },
    finished,
  }

  // When both are done, the counter is 2.
  val bothCounted = workers.forall(w => pc.get(w) == Done) implies x == 2

  // The bad ordering, written down by hand.
  run lostUpdateTest =
    init
      .then(read("A")).then(read("B"))
      .then(write("A")).then(write("B"))
      .expect(x == 1)

  // The fix: read and write in one step, so nobody can come in between.
  action add(w: str): bool = all {
    pc.get(w) == Read,
    x' = x + 1,
    pc' = pc.set(w, Done),
    tmp' = tmp,
  }

  action stepFixed = any {
    { nondet w = workers.oneOf() add(w) },
    finished,
  }
}
