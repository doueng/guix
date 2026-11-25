(ns bbh.super.final
  "A Worker's last message to the person: the last assistant text in its
  conversation's transcript. A Worker writes its report there as its turn
  ends, after super-done! or a stopping ask, so the inbox reads it when it
  builds a view rather than keep a copy in the event log. Reads are cached by
  the transcript's size and modification time, and nothing here calls a model."
  (:require [babashka.fs :as fs]
            [bbh.conversation :as conversation]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def preview-chars
  "How long a preview in a list row or a notification runs, at most."
  200)

(defn last-text
  "The content of the last assistant message with text in LINES, a
  transcript's JSON lines, or nil. Parses from the end, so only the tail."
  [lines]
  (some (fn [line]
          (when-not (str/blank? line)
            (let [{:keys [role content]} (try (json/parse-string line true) (catch Exception _ nil))]
              (when (and (= "assistant" role) (string? content) (not (str/blank? content)))
                (str/trim content)))))
        (rseq (vec lines))))

(def ^:private link-re
  "A URL, or an absolute path that starts a token (no word character, slash
  or dot right before it)."
  #"https?://[^\s`*()\[\]<>\"']+|(?<![\w/.~-])/[\w.~+@%-][^\s`*()\[\]<>\"',;]*")

(defn loosen
  "TEXT with each path and URL set off by whitespace, with nothing attached
  on either side, such as brackets or a sentence's closing period, so
  selecting it in Emacs or on the phone picks it whole. Code or bold marks
  around a lone path or URL go, since they would stick to it."
  [text]
  (let [text (str/replace text #"(`|\*\*)((?:https?://|/)[^\s`*]+)\1" "$2")
        m (re-matcher link-re text)
        out (StringBuilder.)]
    (loop [from 0]
      (if-not (.find m)
        (str (.append out (subs text from)))
        (let [start (.start m)
              token (str/replace (.group m) #"[.,:;!?]+$" "")
              end (+ start (count token))
              before (when (pos? start) (.charAt text (dec start)))
              after (when (< end (count text)) (.charAt text end))]
          (.append out (subs text from start))
          (when (and before (not (Character/isWhitespace (char before)))) (.append out " "))
          (.append out token)
          (when (and after (not (Character/isWhitespace (char after)))) (.append out " "))
          (recur end))))))

(defn preview
  "TEXT as one line of at most N characters (preview-chars by default),
  without Markdown's bold and code marks, cut between words so a path stays
  whole, ending in ' …' when cut."
  ([text] (preview text preview-chars))
  ([text n]
   (let [flat (-> (str text) (str/replace #"\*\*|`" "") (str/replace #"\s+" " ") str/trim)]
     (if (<= (count flat) n)
       flat
       (let [cut (str/last-index-of flat " " (- n 2))]
         (str (str/trimr (subs flat 0 (if (and cut (pos? cut)) cut (- n 2)))) " …"))))))

(defonce ^:private cache (atom {}))

(defn- read-file
  "The loosened last text of transcript F, from the cache while F has the size
  and modification time it had when read."
  [f]
  (let [k [(fs/size f) (.toMillis (fs/last-modified-time f))]
        hit (get @cache f)]
    (if (= k (:key hit))
      (:text hit)
      (let [text (some-> (last-text (fs/read-all-lines f)) loosen)]
        (swap! cache assoc f {:key k :text text})
        text))))

(defn message
  "The last message of conversation DIR, newest rollover first, or nil."
  [dir]
  (some (fn [d]
          (let [f (conversation/file d :messages)]
            (when (fs/exists? f)
              (try (read-file f) (catch java.io.IOException _ nil)))))
        (rseq (conversation/chain dir))))
