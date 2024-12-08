(ns com.fulcrologic.devtools.chrome.target
  "Functions that you use to become a target of your dev tool. A single code base can generate numerous targets,
   so each target must call `target-started!`, but at least ONE target must call `install!`. Ideally you use the
   chrome preload to do this early."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.devtools.js-wrappers :refer [add-window-event-message-listener! set-document-attribute!]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.protocols :as dp]
    [com.fulcrologic.devtools.schemas :as schema :refer [js-map]]
    [com.fulcrologic.devtools.target :refer [set-factory!]]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.devtools.utils :as utils :refer [isoget-in]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn >defn- ? >def]]
    [taoensso.timbre :as log]))

(defonce started?* (atom false))                            ; make sure we only start once

(>def ::devtool-window-event
  [:or
   (js-map
     [constants/content-script->target-key :transit/encoded-string])
   (js-map
     [constants/target->content-script-key :transit/encoded-string])])

(>defn post-window-event! [window-event]
  [::devtool-window-event => :any]
  #?(:cljs (.postMessage js/window window-event "*")))

(>defn push!
  "Push a message from your application to the dev tool it is connected to."
  [data]
  [::schema/devtool-message => :nil]
  #?(:cljs
     (try
       (let [data (utils/strip-lambdas data)]
         (log/info "Target sending message to content script" data)
         (post-window-event! (js {constants/target->content-script-key (encode/write data)})))
       (catch :default e
         (log/error e "Cannot send devtool message.")))))

(>defn connection-config [conn]
  [::dp/DevToolConnection => [:map
                              [:active-requests {:optional true} [:map-of :uuid :async/channel]]
                              [:target-id :uuid]
                              [:tool-type :qualified-keyword]
                              [:description :string]
                              [:async-processor fn?]
                              [:status-handler {:optional true} fn?]]]
  (deref (.-vconfig conn)))

(deftype ChromeConnection [vconfig]
  dp/DevToolConnection
  (-on-status-change [this callback] (vswap! vconfig assoc :status-handler callback))
  (-transmit! [this EQL]
    (let [response-channel (async/chan)
          request-id       (random-uuid)
          {:keys [target-id]} (connection-config this)]
      (vswap! vconfig update :active-requests assoc request-id response-channel)
      (push! {mk/eql        EQL
              mk/target-id  target-id
              mk/request-id request-id})
      (async/go
        (let [timeout (async/timeout 10000)
              [r channel] (async/alts! [response-channel timeout] :priority true)]
          (if (= channel timeout)
            (do
              (log/error "Request to devtool timed out" EQL)
              {mk/error "Request timed out"})
            r))))))

(>defn handle-response [conn {::mk/keys [request-id response] :as message}]
  [::dp/DevToolConnection [:or ::schema/devtool-error ::schema/devtool-response] => :any]
  (let [{:keys [active-requests]} (connection-config conn)
        chan (get active-requests request-id)]
    (when chan
      (try
        (vswap! (.-vconfig conn) update :active-requests dissoc request-id)
        (async/go
          (async/>! chan (or response (select-keys message [mk/error]))))
        (finally
          (async/close! chan))))))

(>defn handle-devtool-request [conn {::mk/keys [target-id request-id eql]}]
  [::dp/DevToolConnection ::schema/devtool-request => :any]
  (let [{:keys [async-processor]} (connection-config conn)]
    (async/go
      (try
        (let [result (async/<! (async-processor eql))]
          (push! {mk/response   result
                  mk/target-id  target-id
                  mk/request-id request-id}))
        (catch :default e
          (log/error e "Devtool client side processor failed.")
          (push! {mk/request-id request-id
                  mk/target-id  target-id
                  mk/error      (ex-message e)}))))))

(defn- handle-devtool-message [conn message]
  [::dp/DevToolConnection ::schema/devtool-message => :nil]
  (let [{my-uuid :target-id
         :keys   [async-processor active-requests]} (connection-config conn)
        {::mk/keys [target-id]} message]
    (when (= my-uuid target-id)
      (let [EQL        (mk/eql message)
            request-id (mk/request-id message)]
        #?(:cljs
           (cond
             (contains? active-requests request-id) (handle-response conn message)

             (and EQL request-id) (handle-devtool-request conn message)

             :else
             (log/error {:msg              "Failed to process devtool message"
                         :event            message
                         :target-id        target-id
                         :EQL              EQL
                         :processor-found? (boolean async-processor)})))))))

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
  [conn]
  #?(:cljs
     (add-window-event-message-listener!
       (fn [event]
         (when (devtool-message? event)
           (handle-devtool-message conn (event-data event)))))))

(deftype ChromeClientConnectionFactory []
  dp/DevToolConnectionFactory
  (-connect! [this {:keys [tool-type description async-processor status-handler] :as config}]
    (let [target-id (random-uuid)
          vconfig   (volatile! (assoc config :target-id target-id))
          conn      (->ChromeConnection vconfig)]
      (listen-for-content-script-messages! conn)
      conn)))

(defn install!
  "Install target support in Chrome so that the content script of your chrome extension will know dev tooling is
   present. This also starts the message processing. This is idempotent, but must be called at least once on the
   web page. Each potential TARGET on the web page must then call `target-started!` to initiate tooling communication.

   This should be called in a PRELOAD so that it gets done before anything else."
  []
  (do
    (log/info "Target setting marker on document")
    (set-document-attribute! constants/chrome-content-script-marker true)
    (when-not @started?*
      (log/info "Installing Fulcrologic Devtools Communication." {})
      (set-factory! (->ChromeClientConnectionFactory))
      (reset! started?* true))))

