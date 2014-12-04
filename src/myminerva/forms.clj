(ns myminerva.forms
  (:use myminerva.util)
  (:require [clojure.string :as str]))

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
 
(defn add-drop-head
  [{season :season, year :year
    :or {season "w" year 2015}}]
  (str/join ["term_in=" (fmt-year-season year season)
             "&RSTS_IN=DUMMY"
             "&assoc_term_in=DUMMY"
             "&CRN_IN=DUMMY"
             "&start_date_in=DUMMY"
             "&end_date_in=DUMMY"
             "&SUBJ=DUMMY"
             "&CRSE=DUMMY"
             "&SEC=DUMMY"
             "&LEVL=DUMMY"
             "&CRED=DUMMY"
             "&GMOD=DUMMY"
             "&TITLE=DUMMY"
             "&MESG=DUMMY"
             "&REG_BTN=DUMMY"
             "&MESG=DUMMY"]))

(defn registered-courses-form
  [{season :season, year :year
    :or {season "w" year 2015}}]
  (str/join ["term_in=" (fmt-year-season year season)]))

(defn add-body [crn]
  (str/join ["&RSTS_IN=RW"
             "&CRN_IN=" crn
             "&assoc_term_in="
             "&start_date_in="
             "&end_date_in="
             "&regs_row=0"]))

(defn drop-body [{year :year season :season} crn]
  (str/join ["&RSTS_IN=DW"
             "&assoc_term_in=" (fmt-year-season year season)
             "&CRN_IN=" crn
             "&start_date_in="
             "&end_date_in="
             "&regs_row=10"]))

(defn add-drop-body
  [{crns :crns add? :add? :as params}]
  (->> (if (string? crns) (vector crns) crns) (map (cond add? add-body :else (partial drop-body params))) (into []) str/join))

(def add-drop-tail (str/join ["&wait_row=0"
                              "&add_row=10"
                              "&REG_BTN=Submit+Changes"]))

(defn add-drop-form [params] {:pre (re-match? #"\d+" (:crns params))}
  (str/join [(add-drop-head params) (add-drop-body params) add-drop-tail]))
