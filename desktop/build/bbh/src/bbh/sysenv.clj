(ns bbh.sysenv
  "The process environment as bbh reads it. Code reads variables through
  `getenv`, and child processes start from `(getenv)`, so a test can replace
  this one function and run hermetically under any session's variables.")

(defn getenv
  ([] (into {} (System/getenv)))
  ([k] (System/getenv k)))
