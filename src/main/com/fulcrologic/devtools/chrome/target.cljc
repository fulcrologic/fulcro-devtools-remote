(ns com.fulcrologic.devtools.chrome.target
  "Functions that you use to become a target of your dev tool. A single code base can generate numerous targets,
   so each target must call `target-started!`, but at least ONE target must call `install!`. Ideally you use the
   chrome preload to do this early."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.devtools.js-wrappers :refer [add-window-event-message-listener!]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.protocols :as dp]
    [com.fulcrologic.devtools.schemas :as schema]
    [com.fulcrologic.devtools.target :refer [DEBUG INSPECT]]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.devtools.utils :as utils :refer [isoget isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn >defn- ?]]
    [com.fulcrologic.guardrails.malli.registry :as gr.reg]
    [malli.core :as m]
    [taoensso.timbre :as log]))

(declare push!)

(defonce started?* (atom false))                            ; make sure we only start once
;; A SINGLE code base can have MANY apps that are each a target, where this ns would be shared among them
(defonce target-processors (volatile! {}))

(defn- handle-devtool-message [message]
  [::schema/devtool-message => :nil]
  (let [target-id  (mk/target-id message)
        EQL        (mk/eql message)
        processor  (get-in @target-processors [target-id :parser])
        request-id (mk/request-id message)]
    (log/info "Target trying to process message from background script:" target-id EQL request-id (boolean processor))
    #?(:cljs
       (if (and EQL processor request-id target-id)
         (async/go
           (try
             (log/info "Using async processor for" target-id)
             (let [result (async/<! (processor EQL))]
               (push! target-id {mk/response   result
                                 mk/request-id request-id}))
             (catch :default e
               (push! target-id {mk/request-id request-id
                                 mk/error      (ex-message e)})
               (log/error e "Devtool client side processor failed."))))
         (log/error {:msg              "Failed to process devtool message"
                     :event            message
                     :target-id        target-id
                     :EQL              EQL
                     :processor-found? (boolean processor)})))))

(>defn- event-data
  "Decode a js event"
  [event]
  [:chrome.event/content-script->target => ::schema/devtool-message]
  (log! "Decoding" event)
  (let [message (some-> event (isoget-in ["data" constants/content-script->target-key "data"]) encode/read)]
    message))

(>defn- devtool-message? [event]
  [:js/event => :boolean]
  #?(:cljs
     (let [^js event event]
       (boolean
         (and (identical? (.-source event) js/window)
           (isoget-in event [:data constants/content-script->target-key]))))
     :clj false))

(defn- listen-for-content-script-messages!
  "Add an event listener for incoming messages that will decode them, run them though the async parser, and then
   push back the result."
  []
  #?(:cljs
     (add-window-event-message-listener!
       (fn [event]
         (when (devtool-message? event)
           (handle-devtool-message (event-data event)))))))

(>defn push!
  "Push a message from your application to the dev tool it is connected to."
  [target-id data]
  [:uuid ::schema/devtool-message => :nil]
  #?(:cljs
     (try
       (let [data (-> data
                    (assoc mk/target-id target-id)
                    (utils/strip-lambdas))]
         (log/info "Target sending message to content script" data)
         (.postMessage js/window (clj->js {constants/target->content-script-key (encode/write data)}) "*"))
       (catch :default e
         (log/error e "Cannot send devtool message.")))))

(defn install!
  "Install target support in Chrome so that the content script of your chrome extension will know dev tooling is
   present. This also starts the message processing. This is idempotent, but must be called at least once on the
   web page. Each potential TARGET on the web page must then call `target-started!` to initiate tooling communication.

   This should be called in a PRELOAD so that it gets done before anything else."
  []
  #?(:cljs
     (do
       (log/info "Target setting marker on document")
       (js/document.documentElement.setAttribute constants/chrome-content-script-marker true)
       (when-not @started?*
         (log/info "Installing Fulcrologic Devtools Communication" {})
         (reset! started?* true)
         (listen-for-content-script-messages!)))))

(>defn target-started!
  "Register your target with the Devtools communications, if available. See `target` ns docstring for
   instructions on enabling/disabling tooling.

   Returns a UUID, which is now the caller's target-id, and must be used when doing out-of-band calls
   to `push!`.  Requests (loads/mutations) from the devtool that are directed to that target-id will
   be processed by the provided `async-pathom-processor`.

   `target-description` is a map of
   transit-serializable data that will be sent to the tool to indicate information about the target.
   Communications will always include the target-id so that if you have multiple targets on page we can
   route the messages accordingly."
  [target-description async-pathom-processor]
  [:string fn? => (? :uuid)]
  (when (and (or DEBUG INSPECT) (not= "disabled" INSPECT))
    (let [target-id (random-uuid)]
      (log/debug "Target activated by application on web page" target-id target-description)
      (vswap! target-processors assoc target-id {:label  target-description
                                                 :parser async-pathom-processor})
      target-id)))

(deftype ChromeConnection [my-target-uuid active-requests push-handler status-handler]
  dp/DevToolConnection
  (-on-status-change [this callback] (vreset! status-handler callback))
  (-transmit! [this EQL]
    (let [response-channel (async/chan)]
      (vswap! active-requests my-target-uuid response-channel)
      (push! my-target-uuid {mk/eql        EQL
                             mk/request-id my-target-uuid})
      (async/go
        (let [timeout (async/timeout 10000)
              [r channel] (async/alts! [response-channel timeout] :priority true)]
          (if (= channel timeout)
            (do
              (log/error "Request to devtool timed out" EQL)
              {mk/error "Request timed out"})
            r))))))

(deftype ChromeDevtoolConnectionFactory []
  dp/DevToolConnectionFactory
  (-connect! [this tool-type {:keys [description async-processor push-handler status-handler]}]
    (let [vstatus-handler (volatile! status-handler)
          target-id       (random-uuid)
          conn            (->ChromeConnection target-id (volatile! {}) push-handler vstatus-handler)]
      conn)))

