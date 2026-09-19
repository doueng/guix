(fn plugin [name spec]
  (let [spec (or spec {})]
    (tset spec 1 name)
    spec))

(fn key [lhs rhs spec]
  (let [spec (or spec {})]
    (tset spec 1 lhs)
    (tset spec 2 rhs)
    spec))

{: plugin : key}
