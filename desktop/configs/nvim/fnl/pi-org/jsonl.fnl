;; Strict LF-only JSONL framing.
;;
;; pi's RPC mode splits records on "\n" only. Unicode separators U+2028 and
;; U+2029 are valid inside JSON strings, so generic line readers (including
;; Neovim's default stdout splitting) are NOT protocol-compliant. We buffer
;; raw chunks and split on "\n" only, stripping a trailing "\r".
;;
;; Mirrors pi's src/modes/rpc/jsonl.ts attachJsonlLineReader.

(local M {})

;; Serialize a table as a single JSONL record terminated by "\n".
(fn serialize [value]
  (.. (vim.json.encode value) "\n"))

;; Create a strict JSONL line reader.
;; Returns (feed, flush). `feed` accepts raw string chunks; `on-line` is
;; invoked once per complete record (without the trailing newline).
(fn reader [on-line]
  (var buffer "")

  (fn emit [line]
    (var l line)
    (when (and (> (length l) 0) (= (l:sub -1) "\r"))
      (set l (l:sub 1 -2)))
    (on-line l))

  (fn feed [chunk]
    (set buffer (.. buffer chunk))
    (var nl (buffer:find "\n" 1 true))
    (while nl
      (emit (buffer:sub 1 (- nl 1)))
      (set buffer (buffer:sub (+ nl 1)))
      (set nl (buffer:find "\n" 1 true))))

  (fn flush []
    (when (not= buffer "")
      (emit buffer)
      (set buffer "")))

  (values feed flush))

(tset M :serialize serialize)
(tset M :reader reader)
M
