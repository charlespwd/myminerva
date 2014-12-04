(ns myminerva.core
  (:use myminerva.util myminerva.forms)
  (:require [clj-http.client :as client]
            [clj-http.cookies :as cookies]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [environ.core :refer [env]]
            [net.cgrand.enlive-html :as html]))

(def ^:dynamic *user* {:username (env :mg-user)
                       :password (env :mg-pass)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants and stuff
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private table-rows-selector [:.pagebodydiv :table :tr])
(def ^:private notes-re #"NOTES:\s*")
(def ^:private base-url "https://horizon.mcgill.ca")
(def ^:private base-path "/pban1")
(def ^:private uri {:login               "/twbkwbis.P_ValLogin"
                    :transcript          "/bzsktran.P_Display_Form?user_type=S&tran_type=V"
                    :search-courses      "/bwskfcls.P_GetCrse"
                    :registered-courses  "/bwskfreg.P_AltPin"
                    :add-courses         "/bwckcoms.P_Regs"})

(def url (->> uri
              (mapval (partial conj [base-url base-path]))
              (mapval str/join)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- login [{user :username, pass :password}]
  (client/post (:login url)
               {:form-params {:sid user, :PIN pass}
                :headers     {"Cookie" "TESTID=set"}}))

(defn auth-cookies
  "The auth-cookies are required to perform any action that requires
  you to be logged into minerva."
  [user]
  (cookies/encode-cookies (:cookies (login user))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transcript bsns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-transcript [user]
  (client/get (:transcript url)
              {:headers {"Cookie" (auth-cookies user)}}))

(defn- fetch-transcript [user]
  (fetch-nodes (request-transcript user) table-rows-selector))

(defn- extract-grade [node]
  (let [columns (html/select [node] [:td])
        rw        (nth columns 0 "")
        course    (nth columns 1 "")
        section   (nth columns 2 "")
        title     (nth columns 3 "")
        credits   (nth columns 4 "")
        grade     (nth columns 6 "")
        class-avg (nth columns 10 "")
        results (map html/text [rw course title section credits grade class-avg])]
    (zipmap [:rw :course :course-title :section :credits :grade :class-avg]
            (map #(str/replace % "\n" " ") results))))

(defn- not-grade? [{course :course}]
  (not (re-match? #"[A-Z]{4} \d+" course)))

(defn get-transcript
  "Obtain the data from the `user` transcript.
  Returns a seq of course hashmaps or nil if login was unsuccessful"
  [user]
  (->> (fetch-transcript user)
       (map extract-grade)
       (remove not-grade?)
       (seq)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search for course
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-courses [user course]
  (client/post (:search-courses url)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (course-selection-form course)}))

(defn- fetch-courses [user course]
  (fetch-nodes (request-courses user course) table-rows-selector))

(defn- notes? [s]
  (re-match? notes-re s))

(defn- extract-course [node]
  (let [columns (html/select [node] [:td])
        full?      (nth columns 0 "")
        crn        (nth columns 1 "")
        dep        (nth columns 2 "")
        c-n        (nth columns 3 "")
        section    (nth columns 4 "")
        kind       (nth columns 5 "")
        title      (nth columns 7 "")
        days       (nth columns 8 "")
        time-slot  (nth columns 9 "")
        instructor (nth columns 16 "")
        status     (nth columns 19 "")
        results (map html/text [full? crn dep c-n section kind title days
                                time-slot instructor status]) 
        notes      (str/trim (html/text (nth columns 1 "")))]
    (if (notes? notes) 
      {:notes (str/replace notes notes-re "")}
      (zipmap [:full? :crn :department :course-number :section :type
             :course-title :days :time-slot :instructor :status]
            (map str/trim (map #(str/replace % "\n" " ") results))))))

(defn- not-course? [{d :days, notes :notes, ts :time-slot}]
  (and (nil? notes)
       (not (re-match? #"TBA" ts))   
       (not (re-match? #"TBA" d))   
       (not (re-match? #"^[MTWRF]{1,3}$" d)))) 

(defn- course-merger [a b]
  (cond (= a b) a
        (and (string? a) (re-match? #"^\W" a)) b
        (and (string? b) (re-match? #"^\W" b)) a
        (vector? a) (conj a b)
        :else (vector a b)))

(defn- course-reducer [[a :as lst] b]
  ; HACK: This is a hack to work with courses with availabilities in two rows.
  ; It checks whether a row's department matches 4 letters in all caps, if so
  ; append to the list the current element, if not, then merge the current
  ; element with the previous one.
  (if (re-match? #"[A-Z]{4}" (or (:department b) ""))
    (conj lst b)
    (conj (rest lst) (merge-with course-merger a b))))

(defn get-courses
  "Search for courses to determine availability, instructor, times, etc."
  [user course]
  (->> (fetch-courses user course)
       (map extract-course)
       (remove not-course?)
       (reduce course-reducer '())
       (map #(update-in % [:full?] (partial = "C")))
       (seq)))

#_(pprint (get-courses *user* {:department "MECH" :season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Check the courses on registration page
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-registered-courses [user semester]
  (client/post (:registered-courses url)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (registered-courses-form semester)}))

(defn- fetch-registered-courses [user course]
  (fetch-nodes (request-registered-courses user course)
               table-rows-selector))

(defn- extract-registered-course [node]
  (let [columns (html/select [node] [:td])
        status  (nth columns 0 "")
        crn     (nth columns 2 "")
        subj    (nth columns 3 "")
        crse    (nth columns 4 "")
        sec     (nth columns 5 "")
        kind    (nth columns 6 "")
        credits (nth columns 8 "")
        title   (nth columns 10 "")
        results (map html/text [status crn subj crse sec kind credits title])
        emsg (nth columns 0 "")
        ecrn (nth columns 1 "")
        err  (map html/text [emsg ecrn])]
    (if-not (and (re-match? #"Web Registered" (first err)) (< 0 (count (first err))))
      (zipmap [:error-message :crn] err)
      (zipmap [:status :crn :department :course-number :section :type :credits :course-title]
              (map str/trim (map #(str/replace % "\n" " ") results))))))

(defn- not-registered-course? [{crn :crn :or {crn ""}}]
  (not (re-match? #"^\d+" crn)))

(defn get-registered-courses
  "Search for web-registered courses for a semester."
  [user semester]
  (->> (fetch-registered-courses user semester)
       (map extract-registered-course)
       (remove not-registered-course?)
       (seq)))

#_(pprint (get-registered-courses *user* {:season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Add/drop courses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-add-drop [user params]
  (client/post (:add-courses url)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (add-drop-form params)}))

(defn- fetch-add-drop [user params]
  (fetch-nodes (request-add-drop user params)
               table-rows-selector))

(defn- add-drop-error-reducer [[a :as lst] m]
  (cond (find a :error-message) (list a)
        (find m :error-message) (list m)
        :else (conj lst m)))

(defn add-drop-courses!
  "Add-drop courses, params is a hash-map with keys :season, :year,
  :crns and :add?. If :add? is true, it adds the course to the list.
  If :add? is false, it drops it.

  Returns a seq of courses if the add/drop was successful or a seq of
  errors if any.

  Errors are of the form {:error-message .* :crn .*} "
  [user params]
  {:pre [(some? (:add? params))]}
  (->> (fetch-add-drop user params)
       (map extract-registered-course)
       (remove not-registered-course?)
       (reduce add-drop-error-reducer '())))

#_(pprint (add-drop-courses! *user* {:season "winter" :year "2015"
                                     :crns "-1" :add? false}))
