(ns myminerva.core
  (:use myminerva.util)
  (:require [clj-http.client :as client]
            [myminerva.forms :as form]
            [clj-http.cookies :as cookies]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [environ.core :refer [env]]
            [net.cgrand.enlive-html :as html]))

(defrecord User [username password])

(def ^{:dynamic true :private true}
  *user* (->User (env :mg-user) (env :mg-pass) ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants and stuff
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private table-rows-selector [:.pagebodydiv :table :tr])
(def ^:private notes-re #"NOTES:\s*")
(def ^:private base-url "https://horizon.mcgill.ca/pban1")
(def ^:private uri {:login               "/twbkwbis.P_ValLogin"
                    :transcript          "/bzsktran.P_Display_Form?user_type=S&tran_type=V"
                    :search-courses      "/bwskfcls.P_GetCrse"
                    :registered-courses  "/bwskfreg.P_AltPin"
                    :add-courses         "/bwckcoms.P_Regs"})

(def ^:private url (->> uri
                        (mapval (partial conj [base-url]))
                        (mapval str/join)))

(defn- http-res->table-rows [http-res]
  (-> http-res (http-res->html-node table-rows-selector)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- login [{user :username, pass :password}]
  (client/post (:login url)
               {:form-params {:sid user, :PIN pass}
                :headers     {"Cookie" "TESTID=set"}}))

(defn user->cookies
  "The authorized cookies are required to perform any action that requires
  you to be logged into minerva. The user must have the following keys:

  :username - the full email of the user
  :password - the password of the user

  Returns a cookie string, or nil if login was unsuccesful."
  [user]
  (let [jar (cookies/encode-cookies (:cookies (login user)))]
    (if (re-match? #"SESSID=.+;" jar) jar)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Authorized protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Authorized
  (auth->cookies [this] "Returns cookie string of given input"))

(extend-protocol Authorized
  User
  (auth->cookies [user] (-> user user->cookies))
  java.util.Map
  (auth->cookies [user] (-> user user->cookies))
  String
  (auth->cookies [s] s) ; assuming cookie string
  nil
  (auth->cookies [_] nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Transcript bsns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-transcript [auth]
  (client/get (:transcript url)
              {:headers {"Cookie" (-> auth auth->cookies)}}))

(defn- extract-grade [node]
  (let [columns (html/select [node] [:td])
        completed? (nth columns 0 "")
        course     (nth columns 1 "")
        section    (nth columns 2 "")
        title      (nth columns 3 "")
        credits    (nth columns 4 "")
        grade      (nth columns 6 "")
        class-avg  (nth columns 10 "")
        results (map html/text [completed? course title section credits grade class-avg])]
    (zipmap [:completed? :course :course-title :section :credits :grade :class-avg]
            (map #(str/replace % "\n" " ") results))))

(defn- not-grade? [{course :course}]
  (not (re-match? #"[A-Z]{4} \d+" course)))

(defn- wrap-course [{course :course :as m}]
  (let [course-number (re-find #"\d+" course)
        department    (re-find #"[A-Z]{1,5}\d*" course)]
    (assoc (dissoc m :course) :course-number course-number :department department)))

(defn- wrap-completed [{completed? :completed? :as m}]
  (assoc m :completed? (not (re-match? #"^RW" completed?))))

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
  [auth]
  (->> (request-transcript auth)
       (http-res->table-rows)
       (map extract-grade)
       (remove not-grade?)
       (map wrap-course)
       (map wrap-completed)
       (seq)))

#_(pprint (get-transcript *user*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Search for course
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-courses [auth options]
  (client/post (:search-courses url)
               {:headers {"Cookie" (-> auth auth->cookies), "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/course-selection-form options)}))

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

(defn- not-valid? [{d :days, notes :notes, ts :time-slot}]
  (and (nil? notes)
       (not (re-match? #"TBA" ts))
       (not (re-match? #"TBA" d))
       (not (re-match? #"^[MTWRF]{1,3}$" d))))

(defn- weird-blank-character? [s] 
  (and (string? s) (re-match? #"^\W" s)))

(defn- course-merger [a b]
  (cond (= a b) a
        (weird-blank-character? a) b
        (weird-blank-character? b) a
        (vector? a) (conj a b)
        :else (vector a b)))

(defn- course? [{dep :department :or {dep ""}}]
  (re-match? #"[A-Z]{4}" dep))
 
(defn- course-reducer [[a :as lst] b]
  (if (course? b) 
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
  [auth options] {:pre [(has-keys? [:season :year :department] options)]}
  (->> (request-courses auth options)
       (http-res->table-rows)
       (map extract-course)
       (remove not-valid?)
       (reduce course-reducer '())
       (map #(update-in % [:full?] (partial = "C")))
       (seq)))

#_(pprint (get-courses *user* {:department "MECH" :season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Check the courses on registration page
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-registered-courses [auth options]
  (client/post (:registered-courses url)
               {:headers {"Cookie" (-> auth auth->cookies), "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/registered-courses-form options)}))

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
  :type           - a string such as lecture, tutorial, ...

  Returns nil if unsuccessful."
  [auth options] {:pre [(has-keys? [:season :year] options)]}
  (->> (request-registered-courses auth options)
       (http-res->table-rows)
       (map extract-registered-course)
       (remove not-registered-course?)
       (seq)))

#_(pprint (get-registered-courses *user* {:season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Add/drop courses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-add-drop [auth options]
  (client/post (:add-courses url)
               {:headers {"Cookie" (-> auth auth->cookies), "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/add-drop-form options)}))

(defn- add-drop-error-reducer [[a :as lst] m]
  (cond (find a :error-message) (list a)
        (find m :error-message) (list m)
        :else (conj lst m)))

(defn- add-drop-courses! [auth options]
  (->> (request-add-drop auth options)
       (http-res->table-rows)
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
  [auth options] {:pre [(has-keys? [:season :year :crns] options)]}
  (add-drop-courses! auth (assoc options :add? true)))

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
  [auth options] {:pre [(has-keys? [:season :year :crns] options)]}
  (add-drop-courses! auth (assoc options :add? false)))
