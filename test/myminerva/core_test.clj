(ns myminerva.core-test
  (:require [clojure.test :refer :all]
            [myminerva.core :refer :all]
            [myminerva.util :refer :all]))  

(def ^:dynamic *invalid-user* {:username "bob", :pass "bob"})
(def ^:dynamic *invalid-course* {:department "invalid"})
(def ^:dynamic *valid-course* {:department "MECH"})

(deftest a-test
  (testing "Login"
    (is (re-match? #"SESSID=[^;]" (auth-cookies *user*)))
    (is (not (re-match? #"SESSID=[^;]" (auth-cookies *invalid-user*))))) 
  (testing "Searching courses should return something on success, nil otherwise."
    (is (nil? (get-courses *user* *invalid-course*)))
    (is (nil? (get-courses *invalid-user* *valid-course*)))
    (is ((complement nil?) (get-courses *user* {:department "MECH"}))))
  (testing "Testing you can get-transcript"
    (is (nil? (get-transcript {})))
    (is (nil? (get-transcript *invalid-user*))))
    (is ((complement nil?) (get-transcript *user*))))  
