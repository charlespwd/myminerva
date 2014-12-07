(ns myminerva.core-test
  (:require [clojure.test :refer :all]
            [environ.core :refer [env]]
            [myminerva.core :refer :all]
            [myminerva.util :refer :all]))


(def ^:dynamic *user*         (-> User (env :mg-user) (env :mg-pass)))
(def ^:dynamic *invalid-user* (-> User "bob" "invalidpass"))
(def ^:dynamic *invalid-course* {:department "invalid"})
(def ^:dynamic *valid-course* {:department "MECH" :year "2015" :season "winter"})

(defn- find-error [m]
  (find m :error-message))

(deftest a-test
  (testing "Login"
    (is (re-match? #"SESSID=[^;]" (auth-cookies *user*)))
    (is (nil? (auth-cookies *invalid-user*))))
  (testing "Searching courses should return something on success, nil otherwise."
    (is (nil? (get-courses *invalid-user* *valid-course*)))
    (is ((complement nil?) (get-courses *user* *valid-course*))))
  (testing "Getting the list of registered courses should return something on success or nil"
    (is (nil? (get-registered-courses *user* {:season "winter" :year "2025"})))
    (is (nil? (get-registered-courses *invalid-user* {:season "winter" :year "2015"})))
    (is ((complement nil?) (get-registered-courses *user* {:season "winter" :year "2015"}))))
  (testing "add-drop is working"
    (is (empty? (filter find-error (add-courses! *user* {:season "winter" :year "2015" :crns "3050"}))))
    (is (empty? (filter find-error (drop-courses! *user* {:season "winter" :year "2015" :crns "3050"}))))
    (is (empty? (filter find-error (add-courses! *user* {:season "winter" :year "2015" :crns ["3050" "709"]}))))
    (is (empty? (filter find-error (drop-courses! *user* {:season "winter" :year "2015" :crns ["3050" "709"]}))))
    (is (not (empty? (filter find-error (add-courses! *user* {:season "winter" :year "2015" :crns "6900"}))))))
  (testing "Testing you can get-transcript"
    (is (nil? (get-transcript {})))
    (is (nil? (get-transcript *invalid-user*))))
    (is ((complement nil?) (get-transcript *user*))))

#_(a-test)
