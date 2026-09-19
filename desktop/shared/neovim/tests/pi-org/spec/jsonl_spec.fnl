;; Unit tests for pi-org.jsonl: strict LF-only JSONL framing.
;;
;; jsonl is pure (no Neovim state beyond vim.json), so these run without an
;; attached UI or buffers.

(local jsonl (require :pi-org.jsonl))
(local eq assert.are.same)
(local is-true assert.is_true)

(describe "pi-org.jsonl serialize"
  (fn []
    (it "produces a single JSON object terminated by \\n"
      (fn []
        (local s (jsonl.serialize {:type :prompt :message :hi}))
        ;; vim.json.encode may sort keys; check structure rather than exact order.
        (is-true (not= (: s :match "\"type\":\"prompt\"") nil))
        (is-true (not= (: s :match "\"message\":\"hi\"") nil))
        (eq "\n" (: s :sub -1))))

    (it "serializes nested tables"
      (fn []
        (local s (jsonl.serialize {:a {:b {:c 1}}}))
        ;; vim.json.encode key order is not guaranteed; just check structure.
        (is-true (not= (: s :find "\"a\"" 1 true) nil))
        (is-true (not= (: s :find "\"b\"" 1 true) nil))
        (is-true (not= (: s :find "\"c\":1" 1 true) nil))
        (eq "\n" (: s :sub -1))))))

(describe "pi-org.jsonl reader"
  (fn []
    (it "delivers one complete line per \\n, without the trailing newline"
      (fn []
        (local lines {})
        (local feed (jsonl.reader (fn [l] (table.insert lines l))))
        (feed "{\"a\":1}\n{\"b\":2}\n")
        (eq ["{\"a\":1}" "{\"b\":2}"] lines)))

    (it "buffers a partial line until the next chunk completes it"
      (fn []
        (local lines {})
        (local feed (jsonl.reader (fn [l] (table.insert lines l))))
        (feed "{\"a\":")
        (eq {} lines)
        (feed "1}\n")
        (eq ["{\"a\":1}"] lines)))

    (it "handles multiple newlines in a single chunk"
      (fn []
        (local lines {})
        (local feed (jsonl.reader (fn [l] (table.insert lines l))))
        (feed "1\n2\n3\n")
        (eq ["1" "2" "3"] lines)))

    (it "strips a trailing \\r (CRLF tolerance)"
      (fn []
        (local lines {})
        (local feed (jsonl.reader (fn [l] (table.insert lines l))))
        (feed "hello\r\n")
        (eq ["hello"] lines)))

    (it "does NOT split on U+2028 / U+2029 inside a value"
      (fn []
        ;; These unicode line/paragraph separators are valid inside JSON strings.
        ;; A naive reader splitting on them would break the record; the strict
        ;; LF-only framer must keep the line intact.
        (local sep2028 (vim.fn.nr2char 0x2028))
        (local sep2029 (vim.fn.nr2char 0x2029))
        (local lines {})
        (local feed (jsonl.reader (fn [l] (table.insert lines l))))
        (local payload (.. "{\"text\":\"a" sep2028 "b" sep2029 "c\"}\n"))
        (feed payload)
        (eq 1 (length lines))
        (is-true (not= (: (. lines 1) :find "a" 1 true) nil))
        (is-true (not= (: (. lines 1) :find "c" 1 true) nil))))

    (it "flush emits a trailing partial line without a newline"
      (fn []
        (local lines {})
        (local (feed flush) (jsonl.reader (fn [l] (table.insert lines l))))
        (feed "partial")
        (eq {} lines)
        (flush)
        (eq ["partial"] lines)))

    (it "flush is a no-op when the buffer is empty"
      (fn []
        (local lines {})
        (local (feed flush) (jsonl.reader (fn [l] (table.insert lines l))))
        (feed "done\n")
        (flush)
        (eq ["done"] lines)))))
