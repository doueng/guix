(ns bbh.gens.limits
  "Generators for bbh.limits-property-test: usage-limit messages as Claude
  Code and Codex word them, each with the reset it names, and transient
  failures. Agents may rewrite this file to explore more of bbh.limits; the
  properties stay in the test file."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen])
  (:import (java.time Instant ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)
           (java.util Locale)))

(def zones ["Europe/London" "America/New_York" "Asia/Tokyo" "UTC" "Australia/Sydney" "America/Los_Angeles" "Asia/Kolkata"])

(def windows
  "[how the message names the window, the window classify should read]"
  [["You've hit your 5-hour limit" "5-hour"] ["You've hit your weekly limit" "weekly"]
   ["You've hit your Opus limit" "Opus"] ["You've hit your limit" nil] ["5-hour limit reached" "5-hour"]
   ["Claude usage limit reached" nil] ["You've reached your weekly limit" "weekly"]])

(defn- fmt [^ZonedDateTime t pattern] (.format t (DateTimeFormatter/ofPattern pattern Locale/ENGLISH)))

(defn- twelve [^ZonedDateTime t]
  (str (fmt t (if (zero? (.getMinute t)) "h" "h:mm")) (str/lower-case (fmt t "a"))))

(def renders
  "How a reset at T is written, by the furthest ahead it can be and stay unambiguous."
  {:twelve {:max-min 1439 :f twelve}
   :twelve-upper {:max-min 1439 :f #(str (fmt % (if (zero? (.getMinute %)) "h" "h:mm")) " " (fmt % "a"))}
   :twenty-four {:max-min 1439 :f #(fmt % "HH:mm")}
   :month-day {:max-min (* 8 1439) :f #(str (fmt % "MMM d") ", " (twelve %))}
   :day-month {:max-min (* 8 1439) :f #(str (fmt % "d MMM") " at " (fmt % "HH:mm"))}
   :weekday {:max-min (* 6 1440) :f #(str (fmt % "EEEE") " " (twelve %))}
   :iso {:max-min (* 8 1439) :f #(str (fmt % "yyyy-MM-dd") " " (fmt % "HH:mm"))}})

(def gen-limit
  "{:text :now :zone :window :want}: a limit message that names WANT, the wall
  clock of its reset in ZONE as a LocalDateTime string, or :epoch / :relative
  ms in WANT-MS."
  (gen/let [now (gen/large-integer* {:min 1767225600000 :max 1893456000000})
            zone (gen/elements zones)
            [prefix window] (gen/elements windows)
            style (gen/elements (concat (keys renders) [:relative :epoch]))
            named-zone? gen/boolean
            sep (gen/elements [" · resets " " · resets at " ". Your limit resets " " | resets "])
            minutes (gen/choose 1 (get-in renders [style :max-min] 4000))]
    (let [z (ZoneId/of zone)
          here (.truncatedTo (ZonedDateTime/ofInstant (Instant/ofEpochMilli now) z) ChronoUnit/MINUTES)
          t (.plusMinutes here minutes)
          want-ms (.toEpochMilli (.toInstant t))]
      (case style
        :relative {:text (str prefix ". Try again in " (quot minutes 60) "h " (mod minutes 60) "m.") :now now :zone zone :window window
                   :want-ms (+ now (* 60000 minutes))}
        :epoch {:text (str "Claude AI usage limit reached|" (quot want-ms 1000)) :now now :zone zone :window nil
                :want-ms (* 1000 (quot want-ms 1000))}
        {:text (str prefix sep ((get-in renders [style :f]) t) (when named-zone? (str " (" zone ")")))
         :now now :zone (if named-zone? "UTC" zone) :real-zone zone :window window :want (str (.toLocalDateTime t))}))))

(def gen-transient
  "Failure texts that pass by themselves, with nothing about a usage limit."
  (gen/elements ["Claude Code: API Error: 529 {\"type\":\"overloaded_error\"}" "Claude Code: API Error: 429 rate_limit_error"
                 "Repeated 529 Overloaded errors" "HTTP 503 Service Unavailable" "API Error: 500 Internal server error"
                 "Request timed out." "Connection error." "socket hang up" "API Error: 502 Bad Gateway"]))

(def gen-other
  "Failures that are neither."
  (gen/one-of [(gen/elements ["Claude Code: prompt is too long" "Claude Code exited with code 1" "Unknown command /x"
                              "The reply hit the model's output limit" "context window exceeded"])
               gen/string-alphanumeric]))
