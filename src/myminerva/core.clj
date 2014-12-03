(ns myminerva.core
  (:use myminerva.util)
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

(def base-url "https://horizon.mcgill.ca")
(def base-path "/pban1")
(def uris {:login               "/twbkwbis.P_ValLogin"
           :transcript          "/bzsktran.P_Display_Form?user_type=S&tran_type=V"
           :select_term         "/bwckgens.p_proc_term_date"
           :search_courses      "/bwskfcls.P_GetCrse"
           :registered_courses  "/bwskfreg.P_AltPin"
           :add_courses         "/bwckcoms.P_Regs"})
(def urls (->> uris
               (mapval (partial conj [base-url base-path]))
               (mapval str/join)))

(def ^:dynamic *generic-table-rows-selector* [:.pagebodydiv :table :tr])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn login [{user :username, pass :password}]
  (client/post (:login urls)
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

(defn request-transcript [user]
  (client/get (:transcript urls)
              {:headers {"Cookie" (auth-cookies user)}}))

(defn fetch-transcript [user]
  (fetch-nodes (request-transcript user) *generic-table-rows-selector*))

(defn extract-grade [node]
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

(defn not-grade? [{course :course}]
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

(defn course-selection-form
  ; wtf minerva...
  [{season :season, year :year, dep :department, course-number :course-number
    :or {season "w" year 2015 dep "COMP" course-number ""}}]
  (str/join ["term_in=" (fmt-year-season year season)
             "&sel_subj=dummy"
             "&sel_day=dummy"
             "&sel_schd=dummy"
             "&sel_insm=dummy"
             "&sel_camp=dummy"
             "&sel_levl=dummy"
             "&sel_sess=dummy"
             "&sel_instr=dummy"
             "&sel_ptrm=dummy"
             "&sel_attr=dummy"
             "&sel_subj=" (str/upper-case dep)
             "&sel_crse=" (str course-number)
             "&sel_title="
             "&sel_schd=%25"
             "&sel_from_cred="
             "&sel_to_cred="
             "&sel_levl=%25"
             "&sel_ptrm=%25"
             "&sel_instr=%25"
             "&sel_attr=%25"
             "&begin_hh=0"
             "&begin_mi=0"
             "&begin_ap=a"
             "&end_hh=0"
             "&end_mi=0"
             "&end_ap=a"]))

(defn request-courses [user course]
  (client/post (:search_courses urls)
               {:headers {"Cookie" (auth-cookies user)
                          "Content-Type" "application/x-www-form-urlencoded"}
                :body (course-selection-form course)}))

(defn fetch-courses [user course]
  (fetch-nodes (request-courses user course) *generic-table-rows-selector*))

(defn extract-course [node]
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
                                time-slot instructor status]) ]
    (zipmap [:full? :crn :department :course-number :section :type
             :course-title :days :time-slot :instructor :status]
            (map str/trim (map #(str/replace % "\n" " ") results)))))

(defn not-course? [{d :days}]
  (not (re-match? #"^[MTWRF]{1,3}$" d)))

(defn course-merger [a b]
  (cond (= a b) a
        (re-match? #"^\W" a) b
        (re-match? #"^\W" b) a
        :else (vector a b)))

(defn course-reducer [[a :as lst] b]
  ; HACK: This is a hack to work with courses with availabilities in two rows.
  ; It checks whether a row's department matches 4 letters in all caps, if so
  ; append to the list the current element, if not, then merge the current
  ; element with the previous one.
  (if (re-match? #"[A-Z]{4}" (:department b))
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
