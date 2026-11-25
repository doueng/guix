(ns bbh.limits
  "What a failed model request means: a usage limit, with when it resets if
  the text says, or a transient failure worth retrying. Pure: the clock and
  the time zone are arguments."
  (:require [clojure.string :as str])
  (:import (java.time DayOfWeek Instant LocalDate LocalTime ZoneId ZonedDateTime)
           (java.time.temporal TemporalAdjusters)))

(def ^:private limit-re
  "Text that names a subscription usage limit, as Claude Code and Codex word it."
  #"(?i)(you'?ve (hit|reached) your|usage limit|(5|five)[- ]hour limit|session limit|weekly limit|opus limit|sonnet limit|limit reached|limit will reset|out of extra usage)")

(def ^:private transient-re
  "Text of a failure that goes away by itself: rate limiting, overload, a server error or a dropped connection."
  #"(?i)(\b429\b|\b529\b|\b50[0234]\b|overloaded|rate[ _-]?limit|too many requests|internal server error|service unavailable|bad gateway|gateway timeout|request timed out|connection (error|reset|refused)|econnreset|socket hang up)")

(defn- window
  "The limit's window as the person knows it, or nil."
  [text]
  (cond (re-find #"(?i)opus" text) "Opus"
        (re-find #"(?i)sonnet" text) "Sonnet"
        (re-find #"(?i)weekly|7[- ]day|seven[- ]day" text) "weekly"
        (re-find #"(?i)(5|five)[- ]hour|session limit" text) "5-hour"))

(def ^:private months
  (zipmap ["jan" "feb" "mar" "apr" "may" "jun" "jul" "aug" "sep" "oct" "nov" "dec"] (range 1 13)))

(def ^:private weekdays
  (zipmap ["mon" "tue" "wed" "thu" "fri" "sat" "sun"] (DayOfWeek/values)))

(defn- zone-of
  "The zone named in parentheses in TEXT, as (Europe/London) or (UTC), else DEFAULT."
  [text default]
  (or (some (fn [[_ z]] (try (ZoneId/of z) (catch Exception _ nil)))
            (re-seq #"\(\s*([A-Za-z]+(?:/[A-Za-z0-9_+-]+)*|UTC|GMT|Z)\s*\)" text))
      default))

(defn- clock-time
  "The LocalTime of 3pm, 3:20pm, 3:20 PM, 15:20 or 9 a.m. in S, or nil."
  [s]
  (when-let [[_ h m ap] (or (re-find #"(?i)\b(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s?m\b\.?" s)
                            (re-find #"\b(\d{1,2}):(\d{2})()\b" s))]
    (let [h (parse-long h) m (if (str/blank? m) 0 (parse-long m))
          ap (some-> ap str/lower-case not-empty)
          h (cond (nil? ap) h (= 12 h) (if (= "a" ap) 0 12) (= "p" ap) (+ h 12) :else h)]
      (when (and (<= 0 h 23) (<= 0 m 59)) (LocalTime/of h m)))))

(defn- month-day
  "[month day year-or-nil] of Nov 28, 28 Nov, November 28th, 2026 or 2026-11-28 in S, or nil."
  [s]
  (or (when-let [[_ y mo d] (re-find #"\b(\d{4})-(\d{2})-(\d{2})\b" s)] [(parse-long mo) (parse-long d) (parse-long y)])
      (when-let [[_ mo d y] (re-find #"(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s*(\d{4})\b)?" s)]
        [(months (str/lower-case mo)) (parse-long d) (some-> y parse-long)])
      (when-let [[_ d mo y] (re-find #"(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:,?\s*(\d{4})\b)?" s)]
        [(months (str/lower-case mo)) (parse-long d) (some-> y parse-long)])))

(defn- relative-ms
  "The milliseconds of `in 2 hours`, `in 3h 20m` or `in 45 minutes` in S, or nil."
  [s]
  (when-let [[_ body] (re-find #"(?i)\bin\s+((?:\d+\s*(?:d|days?|h|hours?|hrs?|m|min|mins|minutes?)\b[\s,and]*)+)" s)]
    (reduce + (for [[_ n unit] (re-seq #"(?i)(\d+)\s*(d|h|m)" body)]
                (* (parse-long n) ({"d" 86400000 "h" 3600000 "m" 60000} (str/lower-case unit)))))))

(defn- reset-clause
  "The words after resets, reset at, try again or available again, up to a
  separator, or nil."
  [text]
  (some-> (re-find #"(?i)\b(?:resets?|try again|available again|back)\b(?:\s+(?:at|on))?\s+(.*)" text)
          second
          (str/split #"\s+·\s+|\.\s|\n")
          first))

(defn- ->ms [^ZonedDateTime t] (.toEpochMilli (.toInstant t)))

(defn reset-at
  "When the limit TEXT describes resets, as epoch ms, read against NOW (epoch
  ms) in ZONE (a ZoneId, used unless TEXT names one), or nil when it says
  nothing that parses. A time alone is its next occurrence, a weekday the next
  such day, a date without a year this year's unless that is long past."
  [text now zone]
  (let [text (str text)]
    (if-let [[_ epoch] (re-find #"\|\s*(\d{10,13})\b" text)]
      (let [n (parse-long epoch)] (if (< n 100000000000) (* 1000 n) n))
      (when-let [clause (reset-clause text)]
        (let [zone (zone-of text zone)
              here (ZonedDateTime/ofInstant (Instant/ofEpochMilli now) zone)
              today (.toLocalDate here)
              time (clock-time clause)
              date (month-day clause)
              wd (some (fn [[k v]] (when (re-find (re-pattern (str "(?i)\\b" k "[a-z]*\\b")) clause) v)) weekdays)
              tomorrow? (re-find #"(?i)\btomorrow\b" clause)
              at (fn [^LocalDate d] (ZonedDateTime/of d (or time LocalTime/MIDNIGHT) zone))]
          (cond
            date (let [[mo d y] date
                       mk #(try (at (LocalDate/of (int %) (int mo) (int d))) (catch Exception _ nil))
                       t (mk (or y (.getYear today)))]
                   (some-> (if (and t (nil? y) (.isBefore t (.minusDays here 2))) (mk (inc (.getYear today))) t) ->ms))
            wd (let [d (.with today (TemporalAdjusters/nextOrSame wd))
                     t (at d)]
                 (->ms (if (.isAfter t here) t (at (.plusWeeks d 1)))))
            tomorrow? (->ms (at (.plusDays today 1)))
            time (let [t (at today)] (->ms (if (.isAfter t here) t (.plusDays t 1))))
            :else (some->> (relative-ms clause) (+ now))))))))

(defn- window-reset
  "The reset of the fullest Claude Code rate-limit window in WINDOWS (its
  rate_limit_event unifiedWindows) once it is used up, as epoch ms, or nil."
  [windows]
  (some->> (vals windows)
           (filter #(and (number? (:utilization %)) (>= (:utilization %) 1.0) (number? (:resetsAt %))))
           (map #(let [r (long (:resetsAt %))] (if (< r 100000000000) (* 1000 r) r)))
           seq (apply max)))

(def max-pause-ms
  "The furthest a reset may lie ahead to be believed: a weekly limit and a day."
  (* 8 86400000))

(defn classify
  "What the failure TEXT means at NOW (epoch ms) in ZONE: {:kind :limit
  :window :until} for a usage limit, :until nil when no reset time parses
  from TEXT or the used-up window in WINDOWS; {:kind :transient} for one to
  retry; nil otherwise."
  [text & {:keys [now zone windows] :or {zone (ZoneId/systemDefault)}}]
  (let [text (str text) now (or now (System/currentTimeMillis))]
    (cond (re-find limit-re text)
          (let [until (or (reset-at text now zone) (window-reset windows))]
            {:kind :limit :window (window text)
             :until (when (and until (< (- now 300000) until (+ now max-pause-ms))) until)})
          (re-find transient-re text) {:kind :transient})))

(defn clock
  "MS as the person reads it in ZONE: 15:20 today, else Fri 28 Nov 15:20."
  ([ms] (clock ms (ZoneId/systemDefault) (System/currentTimeMillis)))
  ([ms zone now]
   (let [t (ZonedDateTime/ofInstant (Instant/ofEpochMilli ms) zone)
         today (.toLocalDate (ZonedDateTime/ofInstant (Instant/ofEpochMilli now) zone))]
     (.format t (java.time.format.DateTimeFormatter/ofPattern (if (= today (.toLocalDate t)) "HH:mm" "EEE d MMM HH:mm")
                                                              java.util.Locale/ENGLISH)))))

(defn label
  "The limit WINDOW as words: 5-hour limit, weekly limit, Opus limit, usage limit."
  [window]
  (str (or window "usage") " limit"))
