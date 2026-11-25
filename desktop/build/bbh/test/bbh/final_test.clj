(ns bbh.final-test
  (:require [babashka.fs :as fs]
            [bbh.conversation :as conversation]
            [bbh.super.final :as final]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

(defn- lines [& messages] (map json/generate-string messages))

(deftest the-last-message-is-the-last-assistant-text
  (is (= "Done, landed."
         (final/last-text (lines {:role "user" :content "brief"}
                                 {:role "assistant" :content "Done, landed."}
                                 {:role "assistant" :content "" :tool_calls [{:id "t"}]}
                                 {:role "tool" :tool_call_id "t" :content "=> nil"})))
      "a tool call with no text, and its result, are not a message to the person")
  (is (nil? (final/last-text (lines {:role "user" :content "brief"}))) "no assistant text, no message")
  (is (nil? (final/last-text [])))
  (is (= "ok" (final/last-text ["{not json" (json/generate-string {:role "assistant" :content "ok"}) ""]))
      "a torn line does not hide the rest"))

(deftest paths-and-urls-stand-alone
  (is (= "See /home/a/b.clj , then /tmp/x.html ." (final/loosen "See `/home/a/b.clj`, then **/tmp/x.html**.")) "marks around a lone path go")
  (is (= "Run `ls /tmp ` and **see /tmp/x **" (final/loosen "Run `ls /tmp` and **see /tmp/x**")) "marks around more than a path stay")
  (is (= "Open ( https://example.com/a?b=1 ) now" (final/loosen "Open (https://example.com/a?b=1) now")))
  (is (= "/a/b at the start, /c/d at the end" (final/loosen "/a/b at the start, /c/d at the end")))
  (is (= "and/or 1/2 kB/s ~/x stay as they are" (final/loosen "and/or 1/2 kB/s ~/x stay as they are"))
      "only a token that starts with a slash is a path"))

(deftest a-preview-is-one-short-line-cut-between-words
  (is (= "Done. See /tmp/r.html . Next" (final/preview "Done. See /tmp/r.html .\n\n**Next**")))
  (let [text (str "word " (apply str (repeat 30 "/very/long/path/segment")) " tail")
        p (final/preview (str (apply str (repeat 40 "abc ")) "/x/y/z end") 50)]
    (is (<= (count p) 50))
    (is (re-find #" …$" p))
    (is (= "word …" (final/preview text 60)) "a path is never cut in two")))

(deftest a-conversation-s-message-comes-from-its-newest-rollover
  (let [base (str (fs/create-temp-dir {:prefix "bbh-final"}))
        first-dir (conversation/dir base "super-W1")
        write (fn [dir & ms] (fs/create-dirs dir) (spit (conversation/file dir :messages) (apply str (map #(str % "\n") (apply lines ms)))))]
    (is (nil? (final/message first-dir)) "no transcript, no message")
    (write first-dir {:role "assistant" :content "First half, see /tmp/a."})
    (is (= "First half, see /tmp/a ." (final/message first-dir)))
    (let [next-dir (conversation/continue! first-dir "super-W1-2")]
      (write next-dir {:role "user" :content "continue"})
      (is (= "First half, see /tmp/a ." (final/message first-dir)) "a rollover with no text yet leaves the older one's")
      (write next-dir {:role "assistant" :content "All done."})
      (is (= "All done." (final/message first-dir))))))
