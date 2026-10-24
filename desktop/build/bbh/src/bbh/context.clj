(ns bbh.context
  "What the model sees of a transcript. The transcript keeps every message. The
  context replaces messages before the latest compaction with its summary and
  shortens the tool output of turns that were already over when it was made.
  Between compactions the context only grows at the end, so every request's
  prefix is the previous request and the provider's prompt cache keeps hitting."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn cap [s n]
  (if (<= (count s) n)
    s
    (let [half (quot n 2)]
      (str (subs s 0 half) "\n[... " (- (count s) n) " chars elided ...]\n" (subs s (- (count s) half))))))

(defn tokens
  "A rough token count: four characters of JSON per token."
  [x]
  (quot (count (json/generate-string x)) 4))

(def ^:private summary-header
  "[bbh] Earlier messages in this conversation were compacted into this summary:\n\n")

(defn turn-start
  "The index of the latest user message, which starts the current turn."
  [messages]
  (or (last (keep-indexed #(when (= "user" (:role %2)) %1) messages)) 0))

(defn view
  "The messages to send, given the transcript, the latest compaction and the
  manifest's :trim-tool-chars. Tool output is shortened only below the
  compaction's :trim-before, so the view changes only when a compaction does."
  [messages {:keys [first-kept summary trim-before]} {:keys [trim-tool-chars]}]
  (let [from (or first-kept 0)
        kept (subvec messages from)
        trim-end (-> (or trim-before 0) (- from) (max 0) (min (count kept)))]
    (cond-> []
      summary (conj {:role "user" :content (str summary-header summary)})
      true (into (map #(cond-> % (= "tool" (:role %)) (update :content cap trim-tool-chars)))
                 (subvec kept 0 trim-end))
      true (into (subvec kept trim-end)))))

(defn cut-point
  "The index of the first message to keep after compacting from index FROM: the
  earliest message whose suffix fits in KEEP-TOKENS, or failing that the latest.
  Tool results stay with the call that asked for them, so a cut never lands on
  one. Nil when nothing before the cut would be summarized."
  [messages from keep-tokens]
  (let [cuts (filterv #(not= "tool" (:role (messages %))) (range (inc from) (count messages)))
        suffix (reductions + (map tokens (rseq messages)))
        fits? (fn [i] (<= (nth suffix (- (count messages) i 1)) keep-tokens))]
    (or (first (filter fits? cuts)) (peek cuts))))

(def summary-prompt
  "You summarize a coding conversation so another model can continue it without the original messages. Write Markdown with these sections, omitting any that would be empty:

## Goal
## Constraints and preferences
## Progress
### Done
### In progress
### Blocked
## Key decisions
## Next steps
## Critical context

Keep the user's requests in their own words. Name every file, function, command and value the work depends on. Record helpers defined in the REPL, since they still exist unless a restart is mentioned. Do not continue the work or address the user.")

(defn- describe [{:keys [role content tool_calls]}]
  (str role ": " (cap (str content) (if (= "tool" role) 2000 8000))
       (str/join (for [c tool_calls]
                   (str "\n[clojure] " (cap (or (:code (json/parse-string (get-in c [:function :arguments]) true))
                                                  (get-in c [:function :arguments]))
                                              4000))))))

(defn summary-request
  "Messages asking a model to fold MESSAGES into PREVIOUS, the earlier summary."
  [previous messages focus]
  [{:role "system" :content summary-prompt}
   {:role "user"
    :content (str (when previous (str "Summary of the conversation before these messages:\n\n" previous "\n\n"))
                  "Messages to summarize:\n\n" (str/join "\n\n" (map describe messages))
                  (when-not (str/blank? focus) (str "\n\nThe user asks the summary to focus on: " focus)))}])
