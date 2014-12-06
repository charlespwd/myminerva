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

(defn- http-res->table-rows [http-res]
  (http-res->html-node http-res table-rows-selector))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Login
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

  Returns a cookie string, or nil if login was unsuccesful."
  [user]
  (let [jar (cookies/encode-cookies (:cookies (login user)))]
    (if (re-match? #"SESSID=.+;" jar) jar)))

#_(auth-cookies *user*)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Transcript bsns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-transcript [cookies]
  (client/get (:transcript url)
              {:headers {"Cookie" cookies}}))

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

(defn get-transcript-from-cookies
  "An optimized version of get-transcript that doesn't require logging
  in provided you already did in the past. Is only ever advantageous
  if you try to do multiple transactions for a user. Works great in a
  when-let for instance.

  See get-transcript for details"
  [cookies]
  (->> cookies
       (request-transcript)
       (http-res->table-rows)
       (map extract-grade)
       (remove not-grade?)
       (map wrap-course)
       (seq)))

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
  (get-transcript-from-cookies (auth-cookies user)))

#_(pprint (get-transcript *user*))
#_(pprint (get-transcript {:username "b" :password "f"}))
#_(when-let [cookies (auth-cookies *user*)] ; wholy shit. I can do it with cookies only.
    (pprint (first (get-transcript-from-cookies cookies)))
    (pprint (first (get-courses cookies {:season "winter" :year 2015 :department "MECH"})))
    (pprint (first (get-transcript-from-cookies cookies)))
    (pprint (first (get-transcript-from-cookies cookies))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Search for course
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-courses [cookies options]
  (client/post (:search-courses url)
               {:headers {"Cookie" cookies, "Content-Type" "application/x-www-form-urlencoded"}
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
  ;; HACK: This is a hack to work with courses with availabilities in
  ;;       two rows. It checks whether a row's department matches 4 letters
  ;;       in all caps, if so append to the list the current element, if not,
  ;;       then merge the current element with the previous one.
  (if (re-match? #"[A-Z]{4}" (or (:department b) ""))
    (conj lst b)
    (conj (rest lst) (merge-with course-merger a b))))

(defn get-courses-from-cookies
  "An optimized version of get-courses which doesn't require logging
  in first. It only needs the session cookies from auth-cookies.

  See get-courses for details"
  [cookies options] {:pre [(has-keys? [:season :year :department] options)]}
  (->> (request-courses cookies options)
       (http-res->table-rows)
       (map extract-course)
       (remove not-course?)
       (reduce course-reducer '())
       (map #(update-in % [:full?] (partial = "C")))
       (seq)))

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
  [user options] {:pre [(has-keys? [:season :year :department] options)]}
  (get-courses-from-cookies (auth-cookies user) options))

#_(pprint (get-courses (auth-cookies *user*) {:department "MECH" :season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Check the courses on registration page
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-registered-courses [cookies options]
  (client/post (:registered-courses url)
               {:headers {"Cookie" cookies, "Content-Type" "application/x-www-form-urlencoded"}
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

(defn get-registered-courses-from-cookies
  "An optimized version of get-registered-courses which doesn't require logging
  in first. It only needs the session cookies from auth-cookies.

  See get-registered-courses for details"
  [cookies options] {:pre [(has-keys? [:season :year] options)]}
  (->> (request-registered-courses cookies options)
       (http-res->table-rows)
       (map extract-registered-course)
       (remove not-registered-course?)
       (seq)))

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
  [user options] {:pre [(has-keys? [:season :year] options)]}
  (get-registered-courses-from-cookies (auth-cookies user) options))

#_(pprint (get-registered-courses *user* {:season "winter" :year "2015"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Add/drop courses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-add-drop [cookies options]
  (client/post (:add-courses url)
               {:headers {"Cookie" cookies, "Content-Type" "application/x-www-form-urlencoded"}
                :body (form/add-drop-form options)}))

(defn- add-drop-error-reducer [[a :as lst] m]
  (cond (find a :error-message) (list a)
        (find m :error-message) (list m)
        :else (conj lst m)))

(defn- add-drop-courses! [cookies options]
  (->> (request-add-drop cookies options)
       (http-res->table-rows)
       (map extract-registered-course)
       (remove not-registered-course?)
       (reduce add-drop-error-reducer '())))

(defn add-courses-from-cookies!
  "An optimized version of add-courses! which doesn't require logging
  in first. It only needs the session cookies from auth-cookies.

  See add-courses! for details"
  [cookies options] {:pre [(has-keys? [:season :year :crns] options)]}
  (add-drop-courses! cookies (assoc options :add? true)))

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
  [user options] {:pre [(has-keys? [:season :year :crns] options)]}
  (add-courses-from-cookies! (auth-cookies user) options))

(defn drop-courses-from-cookies
  "An optimized version of drop-courses! which doesn't require logging
  in first. It only needs the session cookies from auth-cookies.

  See drop-courses! for details"
  [cookies options] {:pre [(has-keys? [:season :year :crns] options)]}
  (add-drop-courses! cookies (assoc options :add? false)))

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
  [user options] {:pre [(has-keys? [:season :year :crns] options)]}
  (drop-courses-from-cookies (auth-cookies user) options))
