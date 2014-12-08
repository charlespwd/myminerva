# myminerva

A Clojure library designed to interact with McGill University's
registration system. It allows you to check your transcript, search
for courses and, more importantly, **add** or **drop** courses.

Take a look at the
[API](http://charlespwd.github.io/myminerva/myminerva.core.html)
for more information on how to use it.

## Usage

The project is hosted on Clojars. To use this project, if you are
using [Leiningen](http://leiningen.org/), include the following in
your `project.clj` dependencies.

      [myminerva "0.1.0-SNAPSHOT"]

## Examples

### Transcript
Here's how you get a user transcript:

```clojure
(get-transcript {:username "neo@mcgill.ca" :password "rabit"})

; => ({:department "MATH",
;      :course-number "262",
;      :class-avg "B",
;      :grade "A",
;      :credits "3",
;      :section "002",
;      :course-title "Intermediate Calculus",
;      :completed? " "}
;     {:department "MECH",
;      :course-number "201",
;      :class-avg "A",
;      :grade "A",
;      :credits "2",
;      :section "001",
;      :course-title "Intro to Mechanical Eng",
;      :completed? " "} ...)
```

### Search
Here's how you search for any courses in a department:

```clojure
(get-courses user {:department "mech"
                   :season "winter" 
                   :year "2015"})

; => ({:department "MECH",
;     :full? false,
;     :section "001",
;     :days " ",
;     :type "Comprehensive Exam",
;     :time-slot "TBA",
;     :status "Active",
;     :instructor "TBA",
;     :crn "10679",
;     :course-number "702",
;     :course-title "Ph.D. Comprehensive Preliminary Oral Examination."}
;    {:department "MECH",
;     :full? false,
;     :section "001",
;     :days " ",
;     :type "Proposal",
;     :time-slot "TBA",
;     :status "Active",
;     :instructor "TBA",
;     :crn "155",
;     :course-number "701",
;     :course-title "Ph.D. Thesis Proposal."}
;     ...)
```

Or for a specific one:
```clojure
(get-courses user {:department "mech"
                   :course-number 208
                   :season "winter"
                   :year "2015"})
```

### Register
Here's how you register for a course:

```clojure
(add-courses! user {:crns "10679" :season "winter" :year "2015"})
```

Or multiple:
```clojure
(add-courses! user {:crns ["10679" "155"] :season "winter" :year "2015"})
```


### Drop
Here's how you drop a course:
```clojure 
(drop-courses! user {:crns "10679" :season "winter" :year "2015"})
```

Or multiple:
```clojure
(drop-courses! user {:crns ["10679" "155"] :season "winter" :year "2015"})
```

For more info, check out the [API](http://charlespwd.github.io/myminerva/myminerva.core.html).

## License

MIT
