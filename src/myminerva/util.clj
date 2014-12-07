(ns myminerva.util
  (:require [net.cgrand.enlive-html :as html]
            [clojure.string :as str]))

(defn filter-by [k v coll]
  (filter #(= v (k %)) coll))

(defn mapval [f m]
  (zipmap (keys m) (map f (vals m))))

(defn mapkeys [f m]
  (zipmap (map f (keys m)) (vals m)))

(defn has-keys? [ks m]
  (every? (comp some? m) ks))

(defn str->html-resource [s]
  (html/html-resource (java.io.StringReader. s)))

(defn http-res->html-resource [res]
  (str->html-resource (:body res)))

(defn http-res->html-node [http-res selector]
  (-> http-res http-res->html-resource (html/select selector)))

(defn re-match? [re s]
  ((complement nil?) (re-find re s)))

(defn season->num [[s]]
  (condp = (str/lower-case s)
                "w" 1
                "s" 5
                "f" 9
                :else -1))

(defn fmt-year-season [year season]
  (str year (format "%02d" (season->num season))))
