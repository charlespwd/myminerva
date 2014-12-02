(ns myminerva.util)

(defn mapval [f hm] 
  (zipmap (keys hm) (map f (vals hm))))

(defn mapkeys [f hm]
  (zipmap (map f (keys hm)) (vals hm))) 
