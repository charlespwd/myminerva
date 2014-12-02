(ns myminerva.core-test
  (:require [clojure.test :refer :all]
            [myminerva.core :refer :all]))

(def ^:dynamic *invalid-user* {:username "bob", :pass "bob"})

(deftest a-test
  (testing "Testing you can get-transcript"
    (is (nil? (get-transcript {})))
    (is (nil? (get-transcript *invalid-user*)))))
    (is ((complement nil?) (get-transcript *user*)))  
