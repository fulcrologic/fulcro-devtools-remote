(ns com.fulcrologic.devtools.chrome.devtool
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.js-support :refer [js log!]]
    [com.fulcrologic.devtools.js-wrappers :refer [add-on-message-listener! post-message! runtime-connect!]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.protocols :as dp]
    [com.fulcrologic.devtools.schemas]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn >defn-]]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def current-tab-id
  "The TAB ID under which your dev tool is running in Chrome Dev Tools"
  #?(:cljs js/chrome.devtools.inspectedWindow.tabId
     :clj  76))

(defonce ^{:doc "An atom containing a map from target ID to target label. Use `watch-targets!` to make this data appear
  in your Fulcro devtool at :devtool/active-targets."}
  active-target-descriptors (atom []))

(defmutation update-active-targets [ts]
  (action [{:keys [state]}]
    (swap! state assoc :devtool/active-targets ts)))

(>defn watch-targets!
  "Installs (or replaces) a watch on the active targets such that a mutation is run on `app` to transact :devtool/active-targets into
   the app state at :devtool/active-targets."
  [app]
  [:fulcro/application => :nil]
  (remove-watch active-target-descriptors ::active-targets)
  (add-watch active-target-descriptors ::active-targets
    (fn [_ _ _ new]
      (comp/transact! app [(update-active-targets new)]))))

(>defn service-worker-message-handler [conn push-handler msg]
  (log! "Devtool layer received service worker message" msg)
  (let [active-requests   (.-vactive-requests conn)
        send-connect-msg! (deref (.-vstatus-callback conn))
        vconnected?       (.-vconnected conn)
        decoded-message   (encode/read msg)
        request-id        (mk/request-id decoded-message)
        active-targets    (mk/active-targets decoded-message)
        response-channel  (when request-id
                            (get @active-requests request-id))]
    (when (seq active-targets)
      (log! "Remembering" (pr-str active-targets))
      (reset! active-target-descriptors active-targets))

    (if response-channel
      (async/go
        (enc/try*
          (async/>! response-channel decoded-message)
          (catch :any _
            (log/error "Failed to deliver response for request" request-id)
            (async/close! response-channel))
          (finally
            (vswap! active-requests dissoc request-id))))
      (if (some? (mk/connected? decoded-message))
        (let [connected? (mk/connected? decoded-message)]
          (when-not connected?
            (vreset! active-target-descriptors []))
          (vreset! vconnected? connected?)
          (send-connect-msg! connected?))
        (if @vconnected?
          (push-handler decoded-message)
          (log! "Cannot send msg. Port not yet connected."))))))

(>defn- listen-to-service-worker! [conn push-handler]
  [::dp/DevToolConnection => :nil]
  (add-on-message-listener! conn (partial service-worker-message-handler conn push-handler))
  nil)

(>defn- send-to-target! [port target-id request-id EQL]
  [:chrome/service-worker-port :uuid :uuid :EQL/expression => :nil]
  (post-message! port (encode/write
                        {mk/request-id request-id
                         mk/target-id  target-id
                         mk/eql        EQL
                         mk/tab-id     current-tab-id}))
  nil)

(deftype ChromeExtensionConnection [port vstatus-callback vactive-requests vconnected]
  dp/DevToolConnection
  (-on-status-change [this callback] (vreset! vstatus-callback callback))
  (-transmit! [this EQL]
    (let [{:keys [params]} (eql/query->ast1 EQL)
          request-id       (random-uuid)
          target-id        (mk/target-id params)
          response-channel (async/chan)]
      (if target-id
        (do
          (vswap! vactive-requests assoc request-id response-channel)
          (send-to-target! port target-id request-id EQL)
          (async/go
            (let [timer (async/timeout 10000)
                  [result c] (async/alts! [response-channel timer] :priority true)]
              (if (= c response-channel)
                (mk/response result)
                {mk/error "Timed out"}))))
        (async/go
          {mk/error "No target ID"})))))

(defn new-chrome-extension-connection
  "Creates a DevtoolConnection from the Chrome dev tool extension itself. In other words, this is used by your devtool.
   in order to construct a devtool-remote."
  ;; TASK: There is no push handler. I need an async pathom processor on BOTH ends
  [push-handler]
  (let [port-name (str constants/devtool-port-name ":" current-tab-id)
        port      (runtime-connect! port-name)
        conn      (->ChromeExtensionConnection port (volatile! nil) (volatile! {}) (volatile! false))]
    (listen-to-service-worker! conn push-handler)
    conn))
