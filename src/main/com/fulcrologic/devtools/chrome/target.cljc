(ns com.fulcrologic.devtools.chrome.target
  "Functions that you use to become a target of your dev tool. A single code base can generate numerous targets,
   so each target must call `target-started!`, but at least ONE target must call `install!`. Ideally you use the
   chrome preload to do this early."
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.target :refer [DEBUG INSPECT]]
    [com.fulcrologic.devtools.utils :as utils :refer [isoget isoget-in]]
    [com.fulcrologic.fulcro.inspect.transit :as encode]
    [taoensso.timbre :as log]))

(declare push!)

(defonce started?* (atom false))                            ; make sure we only start once
;; A SINGLE code base can have MANY apps that are each a target, where this ns would be shared among them
(defonce target-processors (volatile! {}))

(defn- handle-devtool-message [message]
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

(defn- event-data
  "Decode a js event"
  [^js event]
  (js/console.log "Decoding" event)
  ;; Events augment the message and put in the data field.
  ;; All DOM listeners send/receive events
  (let [message (some-> event (isoget-in ["data" constants/content-script->target-key "data"]) encode/read)]
    message))

(defn- devtool-message? [^js event]
  (and (identical? (.-source event) js/window)
    (isoget (.-data event) constants/content-script->target-key)))

(defn- listen-for-content-script-messages!
  "Add an event listener for incoming messages that will decode them, run them though the async parser, and then
   push back the result."
  []
  #?(:cljs
     (.addEventListener js/window "message"
       (fn [^js event]
         (js/console.log "Target helper code saw event" event)
         (when (isoget (.-data event) "describe-targets")
           (.postMessage js/window
             (clj->js {constants/target->content-script-key
                       (encode/write {mk/active-targets
                                      (into {}
                                        (map (fn [k]
                                               [k (get-in @target-processors [k :label] (str "Target:" k))]))
                                        (keys @target-processors))})})
             "*"))
         (when (devtool-message? event)
           (handle-devtool-message (event-data event))))
       false)))

(defn push!
  "Push a message from your application to the dev tool it is connected to."
  [target-id data]
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

(defn target-started!
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
  (when (and (or DEBUG INSPECT) (not= "disabled" INSPECT))
    (let [target-id (random-uuid)]
      (log/debug "Target activated by application on web page" target-id target-description)
      (vswap! target-processors assoc target-id {:label  target-description
                                                 :parser async-pathom-processor})
      target-id)))
