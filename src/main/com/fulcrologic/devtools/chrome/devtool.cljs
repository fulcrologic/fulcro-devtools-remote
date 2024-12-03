(ns com.fulcrologic.devtools.chrome.devtool
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.devtools.constants :as constants]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.devtools.transit :as encode]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.networking.mock-server-remote :as mock-net]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(def current-tab-id
  "The TAB ID under which your dev tool is running in Chrome Dev Tools"
  js/chrome.devtools.inspectedWindow.tabId)

(defonce
  ^{:doc "A map from request ID to async channel where the response is expected."}
  active-requests
  (volatile! {}))

(defonce ^{:doc "An atom containing a map from target ID to target label. Use `watch-targets!` to make this data appear
  in your Fulcro devtool at :devtool/active-targets."}
  active-target-descriptors (atom []))

(defmutation update-active-targets [ts]
  (action [{:keys [state]}]
    (swap! state assoc :devtool/active-targets ts)))

(defn watch-targets!
  "Installs (or replaces) a watch on the active targets such that a mutation is run on `app` to transact :devtool/active-targets into
   the app state at :devtool/active-targets."
  [app]
  (remove-watch active-target-descriptors ::active-targets)
  (add-watch active-target-descriptors ::active-targets
    (fn [_ _ _ new]
      (comp/transact! app [(update-active-targets new)]))))

(defn- listen-to-service-worker! [^js port push-handler]
  (.addListener (.-onMessage port)
    (fn [^js msg]
      (js/console.log "Devtool layer received service worker message" msg)
      (let [decoded-message  (log/spy :info (encode/read msg))
            request-id       (mk/request-id decoded-message)
            active-targets   (mk/active-targets decoded-message)
            response-channel (when request-id
                               (get @active-requests request-id))]
        (when (seq active-targets)
          (js/console.log "Remembering" (pr-str active-targets))
          (reset! active-target-descriptors active-targets))

        (if response-channel
          (async/go
            (try
              (async/>! response-channel decoded-message)
              (catch :default _
                (log/error "Failed to deliver response for request" request-id)
                (async/close! response-channel))
              (finally
                (vswap! active-requests dissoc request-id))))
          (push-handler decoded-message)))))


  (.postMessage port #js {:name "init" :tab-id current-tab-id})

  port)

(defn- send-to-target! [port target-id request-id EQL]
  (.postMessage port #js {"tab-id" current-tab-id
                          "data"   (encode/write
                                     {mk/request-id request-id
                                      mk/target-id  target-id
                                      mk/eql        EQL
                                      mk/tab-id     current-tab-id})}))

(defn devtool-remote
  "A Fulcro remote that will send/receive traffic with the background service worker.
   Messages that were not requested by your devtool via the remote itself will be instead passed to your
   `push-notification-handler`, which is a `(fn [msg])`.

   Reaching a particular target from a `df/load` or `comp/transact!` requires that you include the desired `target-id`
   via the mk/target-id key in load's `:params` or the parameter map of the mutation you try to run.
   "
  [push-notification-handler]
  (let [service-worker-port (js/chrome.runtime.connect #js {:name constants/devtool-port-name})]
    (log/info "Devtool remote created")
    (listen-to-service-worker! service-worker-port push-notification-handler)
    (mock-net/mock-http-server
      {:parser (fn [EQL]
                 (let [{:keys [params] :as ast} (eql/query->ast1 EQL)
                       request-id       (random-uuid)
                       target-id        (mk/target-id params)
                       response-channel (async/chan)]
                   (vswap! active-requests assoc request-id response-channel)
                   (send-to-target! service-worker-port target-id request-id EQL)
                   (async/go
                     (let [timer (async/timeout 10000)
                           [result c] (async/alts! [response-channel timer] :priority true)]
                       (if (= c response-channel)
                         (mk/response result)
                         {mk/error "Timed out"})))))})))
