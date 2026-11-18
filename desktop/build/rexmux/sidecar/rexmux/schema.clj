(ns rexmux.schema
  "The part of JSON Schema that Rex's help document uses for method inputs:
  type (one or several), enum, required, properties, additionalProperties,
  minimum, maximum and items. Enough to refuse a bad call before it reaches
  Rex and to turn missing required arguments into prompts."
  (:require [clojure.string :as str]))

(defn- types [schema]
  (let [t (:type schema)] (cond (nil? t) nil (sequential? t) (set t) :else #{t})))

(defn- type-of [v]
  (cond (nil? v) "null" (boolean? v) "boolean" (integer? v) "integer" (number? v) "number"
        (string? v) "string" (map? v) "object" (sequential? v) "array" :else "unknown"))

(defn- type-ok? [ts v]
  (or (nil? ts) (contains? ts (type-of v)) (and (contains? ts "number") (integer? v))))

(defn errors
  "A vector of {:path [..] :error text} for V against SCHEMA; empty when valid."
  ([schema v] (errors schema v []))
  ([schema v path]
   (let [ts (types schema)]
     (cond
       (not (type-ok? ts v))
       [{:path path :error (str "expected " (str/join " or " (sort ts)) ", got " (type-of v))}]

       (and (:enum schema) (not (some #(= % v) (:enum schema))))
       [{:path path :error (str "must be one of " (str/join ", " (:enum schema)))}]

       (and (number? v) (:minimum schema) (< v (:minimum schema)))
       [{:path path :error (str "must be at least " (:minimum schema))}]

       (and (number? v) (:maximum schema) (> v (:maximum schema)))
       [{:path path :error (str "must be at most " (:maximum schema))}]

       (map? v)
       (let [props (:properties schema)
             known (set (keys props))]
         (vec (concat
               (for [r (:required schema) :let [k (keyword r)] :when (not (contains? v k))]
                 {:path (conj path k) :error "is required"})
               (when (false? (:additionalProperties schema))
                 (for [k (keys v) :when (not (contains? known k))]
                   {:path (conj path k) :error "is not a known argument"}))
               (mapcat (fn [[k x]] (when-let [s (get props k)] (errors s x (conj path k)))) v))))

       (and (sequential? v) (:items schema))
       (vec (mapcat (fn [i x] (errors (:items schema) x (conj path i))) (range) v))

       :else []))))

(defn arg-specs
  "Argument specs of an object SCHEMA, in its property order:
  [{:name kw :type .. :enum .. :required bool :doc ..}]."
  [schema]
  (let [req (set (map keyword (:required schema)))]
    (vec (for [[k s] (:properties schema)]
           (cond-> {:name k :required (contains? req k)}
             (:type s) (assoc :type (:type s))
             (:enum s) (assoc :enum (vec (:enum s)))
             (:description s) (assoc :doc (:description s)))))))

(defn coerce
  "Read TEXT, as typed in the minibuffer, as a value of the arg SPEC's type."
  [spec text]
  (let [ts (types spec) t (str/trim (str text))]
    (cond (and (contains? ts "integer") (re-matches #"-?\d+" t)) (parse-long t)
          (and (contains? ts "number") (re-matches #"-?\d+(\.\d+)?" t)) (parse-double t)
          (and (contains? ts "boolean") (#{"true" "false" "yes" "no"} t)) (contains? #{"true" "yes"} t)
          :else text)))
