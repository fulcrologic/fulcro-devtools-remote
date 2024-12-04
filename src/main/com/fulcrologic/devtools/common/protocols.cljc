(ns com.fulcrologic.devtools.common.protocols
  (:require
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn]]))

(defprotocol DevToolConnection
  (-receive [this] "Private. Use receive. ")
  (-transmit! [this target-id edn] "Private version. Use transmit!"))

(>def ::DevToolConnection [:fn {:error/message "Not a dev tool connection"} #(satisfies? DevToolConnection %)])

(>defn receive
  "Returns a core.async channel that will have the next push notification on it when/if such a message arrives."
  [conn]
  [::DevToolConnection => :async/channel]
  (-receive conn))

(>defn transmit!
  "Low-level method that can send data to the other end of the connection. This is a two-way interaction.

   The `target-id` identifies which of the possible endpoints is talking to the dev tool.

   Returns a core.async channel that will contain the response, or will close on timeout."
  [conn target-id edn]
  [::DevToolConnection :uuid :any => :async/channel]
  (-transmit! conn target-id edn))

(defprotocol DevToolConnectionFactory
  (-connect! [this {:keys [description
                           tool-type
                           async-processor]}]))

(>defn connect! [factory options]
  [::DevToolConnectionFactory [:map
                               [:description :string]
                               [:tool-type :qualified-keyword]
                               [:async-processor fn?]]
   => ::DevToolConnection]
  (-connect! factory options))

(>def ::DevToolConnectionFactory [:fn {:error/message "Connection factory required"} #(satisfies? DevToolConnectionFactory %)])

(def -current-devtool-connection-factory-atom (atom nil))
(>defn set-factory!
  "Configure the dev tool connection factory. Use one of the preloads to do this for you."
  [f]
  [::DevToolConnectionFactory => :nil]
  (reset! -current-devtool-connection-factory-atom f)
  nil)
