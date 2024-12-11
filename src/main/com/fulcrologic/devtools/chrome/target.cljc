(ns com.fulcrologic.devtools.chrome.target
  "Functions that you use to become a target of your dev tool. A single code base can generate numerous targets,
   so each target must call `target-started!`, but at least ONE target must call `install!`. Ideally you use the
   chrome preload to do this early."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.connection :as cc]
    [com.fulcrologic.devtools.common.js-support :refer [js]]
    [com.fulcrologic.devtools.common.js-wrappers :refer [add-window-event-message-listener! post-window-message! set-document-attribute!]]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.schemas :as schema :refer [js-map]]
    [com.fulcrologic.devtools.common.target :as ct :refer [set-factory!]]
    [com.fulcrologic.devtools.common.target-default-mutations]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.devtools.common.utils :as utils :refer [isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >def >defn]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defonce started?* (atom false))                            ; make sure we only start once

(>def ::devtool-window-event
  [:or
   (js-map
     [constants/content-script->target-key :transit/encoded-string])
   (js-map
     [constants/target->content-script-key :transit/encoded-string])])

(>defn push!
  "Push a message from your application to the dev tool it is connected to."
  [data]
  [::schema/devtool-message => :nil]
  (enc/try*
    (let [data (utils/strip-lambdas data)]
      (post-window-message! (js {constants/target->content-script-key (encode/write data)})))
    (catch :any e
      (log/error e "Cannot send devtool message."))))

(>defn event-data
  "Decode a js event"
  [event]
  [:chrome.event/content-script->target => ::schema/devtool-message]
  (some-> event (isoget-in ["data" constants/content-script->target-key]) encode/read))

(>defn devtool-message? [event]
  [:js/event => :boolean]
  (boolean (isoget-in event [:data constants/content-script->target-key])))

(defn- listen-for-content-script-messages!
  "Add an event listener for incoming messages that will decode them, run them though the async parser, and then
   push back the result."
  [conn]
  (add-window-event-message-listener!
    (fn [event]
      (when (devtool-message? event)
        (cc/handle-devtool-message conn (event-data event))))))

(deftype ChromeClientConnectionFactory []
  dp/DevToolConnectionFactory
  (-connect! [this {:keys [target-id] :as config}]
    (let [target-id (or target-id (random-uuid))
          send-ch   (async/chan (async/dropping-buffer 10000))
          vconfig   (volatile! (assoc config :target-id target-id
                                             :send-ch send-ch))
          conn      (cc/->Connection vconfig)]
      (async/go-loop []
        (let [msg (log/spy :info "send message" (async/<! send-ch))]
          (push! msg))
        (recur))
      (listen-for-content-script-messages! conn)
      conn)))

(defn install!
  "Install target support in Chrome so that the content script of your chrome extension will know dev tooling is
   present. This also starts the message processing. This is idempotent, but must be called at least once on the
   web page. Each potential TARGET on the web page must then call `target-started!` to initiate tooling communication.

   This should be called in a PRELOAD so that it gets done before anything else."
  []
  (do
    (set-document-attribute! constants/chrome-content-script-marker true)
    (when-not @started?*
      (log/info "Installing Fulcrologic Devtools Communication." {})
      (set-factory! (->ChromeClientConnectionFactory))
      (reset! started?* true))))

