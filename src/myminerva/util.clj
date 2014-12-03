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

(defn re-match? [re s]
  ((complement nil?) (re-find re s)))
