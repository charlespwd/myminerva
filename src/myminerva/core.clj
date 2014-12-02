(ns myminerva.core
  (:require [clj-http.client :as client]
            [clj-http.cookies :as cookies]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [myminerva.util :refer :all]
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
           :select_courses      "/bwskfcls.P_GetCrse"
           :registered_courses  "/bwskfreg.P_AltPin"
           :add_courses         "/bwckcoms.P_Regs"})
(def urls (->> uris
               (mapval (partial conj [base-url base-path]))
               (mapval str/join)))

(def ^:dynamic *transcript-table-selector* [:.pagebodydiv :table :tr])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn login [{user :username, pass :password}]
  (client/post (:login urls)
               {:form-params {:sid user, :PIN pass}
                :headers     {"Cookie" "TESTID=set"}}))

(defn auth-cookies [user]
  (:cookies (login user)))

(defn request-transcript [user]
  (client/get (:transcript urls)
              {:headers {"Cookie"
                         (cookies/encode-cookies (auth-cookies user))}}))

(defn get-transcript-page [user]
  (:body (request-transcript user)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn str->html-resource [s]
  (html/html-resource (java.io.StringReader. s)))

(defn fetch-transcript [user]
  (-> (get-transcript-page user)
      (str->html-resource)
      (html/select *transcript-table-selector*)))

(defn extract-transcript [node]
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

(defn not-course? [{course :course}]
  (nil? (re-find #"[A-Z]{4} \d+" course)))

(defn get-transcript [user]
    (->> (fetch-transcript user)
         (map extract-transcript)
         (remove not-course?)))

(pprint (get-transcript *user*))
