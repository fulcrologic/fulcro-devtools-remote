(ns com.fulcrologic.devtools.protocols
  (:require
    [com.fulcrologic.devtools.schemas]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn]]))

(defprotocol DevToolConnection
  (-on-status-change [this callback] "Private version. Use on-status-change")
  (-transmit! [this EQL] "Private version. Use transmit!"))

(>def ::DevToolConnection [:fn {:error/message "Not a dev tool connection"} #(satisfies? DevToolConnection %)])

(>defn on-status-change
  "Register a callback function which is called when the status changes (open/closed).
    The callback is a `(fn [boolean])`. True means connected, false disconnected."
  [conn callback]
  [::DevToolConnection fn? => :nil]
  (-on-status-change conn callback)
  nil)

(>defn transmit!
  "Low-level method that can run arbitrary EQL against the dev tool.
   Returns a core.async channel that contains the EQL result."
  [conn EQL]
  [::DevToolConnection vector? => :async/channel]
  (-transmit! conn EQL))

(defprotocol DevToolConnectionFactory
  (-connect! [this {:keys [description
                           tool-type
                           async-processor]}]))

(>def ::DevToolConnectionFactory [:fn #(satisfies? % DevToolConnectionFactory)])

(>defn connect! [factory options]
  [::DevToolConnectionFactory [:map
                               [:description :string]
                               [:tool-type :qualified-keyword]
                               [:async-processor fn?]]
   => ::DevToolConnection]
  (-connect! factory options))

(>def ::DevToolConnectionFactory [:fn #(satisfies? % DevToolConnectionFactory)])

(def -current-devtool-connection-factory-atom (atom nil))
(>defn set-factory!
  "Configure the dev tool connection factory. Use one of the preloads to do this for you."
  [f]
  [::DevToolConnectionFactory => :nil]
  (reset! -current-devtool-connection-factory-atom f)
  nil)
