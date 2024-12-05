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
  #?(:cljs (:require-macros com.fulcrologic.devtools.target))
  (:require
    [com.fulcrologic.devtools.schemas]
    [com.fulcrologic.guardrails.malli.core :refer [>defn => >def]]))

(def INSPECT
  #?(:clj  (System/getProperty "com.fulcrologic.devtools.target.INSPECT")
     :cljs (goog-define INSPECT false)))

(def DEBUG #?(:cljs goog.DEBUG :clj (System/getProperty "com.fulcrologic.devtools.target.DEBUG")))

(defprotocol DevToolConnection
  (transmit! [this EQL]
    "Low-level method that can run arbitrary EQL against the dev tool. Returns a core.async channel that
     contains the EQL result."))

(>def ::DevToolConnection [:fn #(satisfies? % DevToolConnection)])

(defprotocol DevToolConnectionFactory
  (-connect! [this target-description async-pathom-processor]))

(>def ::DevToolConnectionFactory [:fn #(satisfies? % DevToolConnectionFactory)])

(def -current-devtool-connection-factory-atom (atom nil))
(>defn set-factory!
  "Configure the dev tool connection factory. Use one of the preloads to do this for you."
  [f]
  [::DevToolConnectionFactory => :nil]
  (reset! -current-devtool-connection-factory-atom f)
  nil)

(>defn connect!
  "Register your target with the Devtools communications, if available. See ns docstring for
   instructions on enabling/disabling tooling.

   Returns a DevToolConnection which can execute transactions and loads against the remote dev tool.

   `target-description` is a string that will be sent to label the target.

   Communications will always include the target-id so that if you have multiple targets on page we can
   route the messages accordingly.

   Will do NOTHING if the devtool connection factory has not been initialized. Be sure you have done a preload
   to configure the factory."
  [target-description async-pathom-processor]
  [:string fn? => :nil]
  (when @-current-devtool-connection-factory-atom
    (-connect! @-current-devtool-connection-factory-atom target-description async-pathom-processor)))

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

