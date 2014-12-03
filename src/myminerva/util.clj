(ns myminerva.util
  (:require [net.cgrand.enlive-html :as html]))

(defn mapval [f hm]
  (zipmap (keys hm) (map f (vals hm))))

(defn mapkeys [f hm]
  (zipmap (map f (keys hm)) (vals hm)))

(defn str->html-resource [s]
  (html/html-resource (java.io.StringReader. s)))

(defn http-res->html-resource [res]
  (str->html-resource (:body res)))

(defn season->num [[s]]
  {:pre (re-match? #"[wWsSfF]" s)}
  (condp = (str/lower-case s)
                "w" 1
                "s" 5
                "f" 9))

(defn season->month-str [s]
  (format "%02d" (season->num s)))

(defn fmt-year-season [year season]
  (str year (season->month-str season)))
 
(defn fetch-nodes [http-res selector]
  (-> http-res http-res->html-resource (html/select selector)))

(defn re-match? [re s]
  ((complement nil?) (re-find re s)))
