(ns myminerva.core
  (:use myminerva.util)
  (:require [clj-http.client :as client]
            [myminerva.forms :as form]
            [clj-http.cookies :as cookies]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [environ.core :refer [env]]
            [net.cgrand.enlive-html :as html]))

(def ^{:dynamic true :private true}
  *user* {:username (env :mg-user)
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

(def ^:private url (->> uri
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
  you to be logged into minerva. The user must have the following keys:

  :username - the full email of the user
  :password - the password of the user

  Returns a cookie string"
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
    (zipmap [:completed? :course :course-title :section :credits :grade :class-avg]
            (map #(str/replace % "\n" " ") results))))

(defn- not-grade? [{course :course}]
  (not (re-match? #"[A-Z]{4} \d+" course)))

(defn- wrap-course [{course :course :as m}]
  (let [course-number (re-find #"\d+" course)
        department    (re-find #"[A-Z]{1,5}\d*" course)]
    (assoc (dissoc m :course) :course-number course-number :department department)))

(defn get-transcript
  "Obtain a seq of courses maps from the user's transcript.

  Courses contain the following keys mapped to strings unless specified otherwise:

  :class-avg     - the class average in letter form
  :completed?    - boolean identifying if the course is completed or not
  :course-number - the course number
  :course-title  - the title of the course
  :credits       - the number of credits of the course
  :department    - the department identifier. e.g. 'MECH', 'COMP', ...
  :grade         - the user's grade in letter form
  :section       - the section number taken

  Returns nil if the operation was unsuccessful."
  [user]
  (->> (fetch-transcript user)
       (map extract-grade)
       (remove not-grade?)
       (map wrap-course)
       (seq)))

#_(pprint (get-transcript *user*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search for course
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-courses [user options]
  (client/post (:search-courses url)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/course-selection-form options)}))

(defn- fetch-courses [user options]
  (fetch-nodes (request-courses user options) table-rows-selector))

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
  "Search for courses to determine availability, instructor, times,
  etc. The options take the following keys:

  :season         - required, #\"winter|fall|summer\"
  :year           - required, this one is pretty obvious
  :department     - required, the department identifier. e.g. 'MECH'
  :course-number  - optional, the course number

  Returns a seq of courses with the following keys and values as
  strings unless specified otherwise:

  :course-number  - the course number
  :course-title   - the title of the course
  :credits        - the number of credits of the course
  :crn            - the course identification number for add/drop purposes
  :days           - a string or vector of strings matching #\"[MTWRF]\"+
  :department     - the department identifier. e.g. 'MECH', 'COMP', ...
  :full?          - a boolean representing if the course is full or not
  :grade          - the user's grade in letter form
  :instructor     - the professor giving the course
  :notes          - nil, string or vector of strings of comments on the course
  :section        - the section number taken
  :status         - is the course is active, cancelled, ... ?
  :time-slot      - a string or vector of strings representing the times
                    allocated for the course
  :type           - a string such as lecture, tutorial, ...

  Returns nil if the search was unsuccessful."
  [user options]
  {:pre [(every? (comp some? options) [:season :year :department])]}
  (->> (fetch-courses user options)
       (map extract-course)
       (remove not-course?)
       (reduce course-reducer '())
       (map #(update-in % [:full?] (partial = "C")))
       (seq)))

#_(pprint (get-courses *user* {:department "MECH" :season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Check the courses on registration page
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-registered-courses [user options]
  (client/post (:registered-courses url)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/registered-courses-form options)}))

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
  "Search for web-registered courses for a semester. Returns a seq of
  courses with the following keys:

  :course-number  - the course number
  :course-title   - the title of the course
  :credits        - the number of credits of the course
  :crn            - the course identification number for add/drop purposes
  :department     - the department identifier. e.g. 'MECH', 'COMP', ...
  :section        - the section number taken
  :status         - is the course is active, cancelled, ... ?
  :type           - a string such as lecture, tutorial, ..."
  [user options]
  (->> (fetch-registered-courses user options)
       (map extract-registered-course)
       (remove not-registered-course?)
       (seq)))

#_(pprint (get-registered-courses *user* {:season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Add/drop courses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-add-drop [user options]
  (client/post (:add-courses url)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/add-drop-form options)}))

(defn- fetch-add-drop [user options]
  (fetch-nodes (request-add-drop user options)
               table-rows-selector))

(defn- add-drop-error-reducer [[a :as lst] m]
  (cond (find a :error-message) (list a)
        (find m :error-message) (list m)
        :else (conj lst m)))

(defn- add-drop-courses!
  [user options]
  (->> (fetch-add-drop user options)
       (map extract-registered-course)
       (remove not-registered-course?)
       (reduce add-drop-error-reducer '())))

(defn add-courses!
  "Register for courses for a given semester and year. The options must have
  the following keys:

  :crns   - a seq of or a single crn to add
  :season - #\"winter|fall|summer\"
  :year   - this one is pretty obvious

  Returns a seq of courses if the add/drop was successful or a seq of
  errors if any.

  Errors have the following keys

  :error-message  - the identifier of the error as per minerva
  :crn            - the crn attached to the error"
  [user options]
  {:pre [(every? (comp some? options) [:season :year :crns])]}
  (add-drop-courses! user (assoc options :add? true)))

(defn drop-courses!
  "Drop courses for a given semester and year. The options must have the
  following keys:

  :crns   - a seq of or a single crn to add
  :season - #\"winter|fall|summer\"
  :year   - this one is pretty obvious

  Returns a seq of courses if the add/drop was successful or a seq of
  errors if any.

  Errors have the following keys

  :error-message  - the identifier of the error as per minerva
  :crn            - the crn attached to the error"
  [user options]
  {:pre [(every? (comp some? options) [:season :year :crns])]}
  (add-drop-courses! user (assoc options :add? false)) )

#_(pprint (add-drop-courses! *user* {:season "winter" :year "2015"
                                     :crns "-1" :add? false}))
