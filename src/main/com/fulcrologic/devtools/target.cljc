(ns com.fulcrologic.devtools.target
  "
  Target helpers for redacting tool code in production builds.

  The macros in the ns emit nothing when goog.DEBUG and INSPECT (in this ns) are false-ish. In CLJS, this forces the
  tool code to be present in all but release builds.

  But, you can ALWAYS enable tools in production by adding the following to your shadow-cljs compiler config:

  :closure-defines {\"com.fulcrologic.devtools.target.INSPECT\" true}

  Setting INSPECT to the value \"disabled\" FORCES these macros to emit nothing, ignoring other settings. Letting
  you turn off tooling for things like performance profiling.

  If your tool TARGET is CLJ, then use Java properties
  \"com.fulcrologic.devtools.target.DEBUG\" and \"com.fulcrologic.devtools.target.INSPECT\" for the same effects.
  "
  #?(:cljs (:require-macros com.fulcrologic.devtools.target)))

(defonce registered-target-processors (atom {}))

(def INSPECT
  #?(:clj  (System/getProperty "com.fulcrologic.devtools.target.INSPECT")
     :cljs (goog-define INSPECT false)))

(def DEBUG #?(:cljs goog.DEBUG :clj (System/getProperty "com.fulcrologic.devtools.target.DEBUG")))

#?(:clj
   (defmacro ido
     "Like `do`, but acts like a comment when devtools are disabled (See ns docstring)."
     [& body]
     `(when (and (or DEBUG INSPECT) (not= "disabled" INSPECT))
        (try
          ~@body
          (catch :default ~'e)))))

(defn cljs?
  "Returns true when env is a cljs macro &env"
  [env]
  (boolean (:ns env)))

#?(:clj
   (defmacro ilet
     "Like `clojure.core/let`, but acts like `comment` when disabled. See ns docstring."
     [bindings & body]
     (when (cljs? &env)
       `(ido
          (let ~bindings
            ~@body)))))

