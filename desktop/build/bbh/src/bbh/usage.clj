(ns bbh.usage
  "Subscription limits and spend, after Pi's pi-usage extension."
  (:require [babashka.http-client :as http]
            [bbh.auth :as auth]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- get-json [url headers]
  (let [{:keys [status body]} (http/get url {:headers headers :throw false :timeout 20000})]
    (if (= 200 status)
      (json/parse-string body true)
      (throw (ex-info (str "HTTP " status) {})))))

(defn- reset-label [epoch-seconds]
  (when epoch-seconds
    (let [minutes (max 0 (quot (- (* 1000 (long epoch-seconds)) (System/currentTimeMillis)) 60000))]
      (cond (< minutes 60) (str ", resets in " minutes "m")
            (< minutes (* 48 60)) (str ", resets in " (Math/round (/ minutes 60.0)) "h")
            :else (str ", resets in " (Math/round (/ minutes 1440.0)) "d")))))

(defn- window-lines [windows]
  (for [{:keys [label used resets-at]} windows]
    (str "  " label " window: " (Math/round (double used)) "% used" (reset-label resets-at))))

(def ^:private claude-windows
  {:five_hour "5h" :seven_day "7d" :seven_day_opus "7d opus" :seven_day_sonnet "7d sonnet"})

(defn- parse-instant [s]
  (some-> s java.time.Instant/parse .getEpochSecond))

(defn- claude-usage [limits]
  (let [{:keys [accessToken expiresAt subscriptionType]} (auth/claude-oauth)
        windows (if (and accessToken (> (or expiresAt 0) (System/currentTimeMillis)))
                  (let [payload (get-json "https://api.anthropic.com/api/oauth/usage"
                                          {"Authorization" (str "Bearer " accessToken)
                                           "anthropic-beta" "oauth-2025-04-20"})]
                    (for [[k label] claude-windows
                          :let [{:keys [utilization resets_at]} (get payload k)]
                          :when (number? utilization)]
                      {:label label :used utilization :resets-at (parse-instant resets_at)}))
                  (for [[k label] claude-windows
                        :let [{:keys [utilization resetsAt]} (get limits k)]
                        :when (number? utilization)]
                    {:label label :used (* 100 utilization) :resets-at resetsAt}))]
    (when (seq windows)
      (into [(str "Claude (" (or subscriptionType "subscription") "):")] (window-lines windows)))))

(defn- codex-usage [_]
  (when-let [token (auth/oauth-token "openai-codex")]
    (let [payload (get-json "https://chatgpt.com/backend-api/wham/usage" {"Authorization" (str "Bearer " token)})
          windows (for [{:keys [used_percent limit_window_seconds reset_at]} (vals (:rate_limit payload))
                        :when (number? used_percent)]
                    {:label (cond (nil? limit_window_seconds) "window"
                                  (< limit_window_seconds 3600) (str (quot limit_window_seconds 60) "m")
                                  (< limit_window_seconds 86400) (str (Math/round (/ limit_window_seconds 3600.0)) "h")
                                  :else (str (Math/round (/ limit_window_seconds 86400.0)) "d"))
                     :used used_percent :resets-at reset_at})]
      (into [(str "OpenAI Codex (" (or (:plan_type payload) "unknown plan") "):")] (window-lines windows)))))

(defn- openrouter-usage [_]
  (when-let [k (auth/api-key "OPENROUTER_API_KEY" "openrouter")]
    (let [{:keys [total_credits total_usage]} (:data (get-json "https://openrouter.ai/api/v1/credits"
                                                               {"Authorization" (str "Bearer " k)}))]
      ["OpenRouter:" (format "  $%.2f left of $%.2f" (- total_credits total_usage) (double total_credits))])))

(def ^:private sources
  [["claude" claude-usage] ["codex" codex-usage] ["openrouter" openrouter-usage]])

(defn report
  "LIMITS are Claude Code's latest rate-limit windows, SPENT this session's cost."
  [{:keys [limits spent]}]
  (let [sections (pmap (fn [[id f]]
                         (try (f limits)
                              (catch Exception e [(str id ": " (ex-message e))])))
                       sources)]
    (str/join "\n" (concat (apply concat sections)
                            [(format "This session: $%.4f" (double (or spent 0)))]))))
