(ns com.fulcrologic.devtools.chrome.target
  "Functions that you use to become a target of your dev tool. A single code base can generate numerous targets,
   so each target must call `target-started!`, but at least ONE target must call `install!`. Ideally you use the
   chrome preload to do this early."
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.js-support :refer [js]]
    [com.fulcrologic.devtools.common.js-wrappers :refer [add-window-event-message-listener! post-window-message! set-document-attribute!]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.schemas :as schema :refer [js-map]]
    [com.fulcrologic.devtools.common.target :refer [set-factory!]]
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

(declare connection-config)

(deftype ChromeConnection [vconfig]
  dp/DevToolConnection
  (-transmit! [this target-id EQL]
    (let [response-channel (async/chan)
          request-id       (random-uuid)
          {:keys [target-id]} (connection-config this)]
      (vswap! vconfig update :active-requests assoc request-id response-channel)
      (push! {mk/request    EQL
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

(>defn connection-config [^Object conn]
  [::dp/DevToolConnection => [:map
                              [:active-requests {:optional true} [:map-of :uuid :async/channel]]
                              [:target-id :uuid]
                              [:tool-type :qualified-keyword]
                              [:description :string]
                              [:async-processor fn?]]]
  (deref (.-vconfig conn)))

(>defn handle-response [^Object conn {::mk/keys [request-id response] :as message}]
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

(>defn handle-devtool-request [conn {::mk/keys [target-id request-id request]}]
  [::dp/DevToolConnection ::schema/devtool-request => :any]
  (let [{:keys [async-processor]} (connection-config conn)]
    (async/go
      (try
        (let [result (async/<! (async-processor request))]
          (push! {mk/request-id request-id
                  mk/target-id  target-id
                  mk/response   result}))
        (catch :default e
          (log/error e "Devtool client side processor failed.")
          (push! {mk/request-id request-id
                  mk/target-id  target-id
                  mk/error      (ex-message e)}))))))

(defn- handle-devtool-message [^:clj conn message]
  [::dp/DevToolConnection ::schema/devtool-message => :any]
  (let [{my-uuid :target-id
         :keys   [async-processor active-requests]} (connection-config conn)
        connected? (mk/connected? message)
        target-id  (mk/target-id message)]
    (if (some? connected?)
      (async-processor [(bi/devtool-connected {:connected? connected?})])
      (when (= my-uuid target-id)
        (let [EQL        (mk/request message)
              request-id (mk/request-id message)]
          (cond
            (contains? active-requests request-id) (handle-response conn message)
            (and EQL request-id) (handle-devtool-request conn message)
            :else (log/error message)))))))

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
        (handle-devtool-message conn (event-data event))))))

(deftype ChromeClientConnectionFactory []
  dp/DevToolConnectionFactory
  (-connect! [this {:keys [target-id] :as config}]
    (let [target-id (or target-id (random-uuid))
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
    (set-document-attribute! constants/chrome-content-script-marker true)
    (when-not @started?*
      (log/info "Installing Fulcrologic Devtools Communication." {})
      (set-factory! (->ChromeClientConnectionFactory))
      (reset! started?* true))))

