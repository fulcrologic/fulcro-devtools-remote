(ns com.fulcrologic.devtools.chrome.devtool
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.devtools.common.constants :as constants]
    [com.fulcrologic.devtools.common.devtool-default-mutations]
    [com.fulcrologic.devtools.common.fulcro-devtool-remote :as fdr]
    [com.fulcrologic.devtools.common.js-wrappers :refer [add-on-message-listener! post-message! runtime-connect!]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.protocols :as dp]
    [com.fulcrologic.devtools.common.resolvers :as resolvers]
    [com.fulcrologic.devtools.common.schemas]
    [com.fulcrologic.devtools.common.transit :as encode]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.guardrails.malli.core :refer [=> >defn]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def current-tab-id
  "The TAB ID under which your dev tool is running in Chrome Dev Tools"
  #?(:cljs js/chrome.devtools.inspectedWindow.tabId
     :clj  76))

(>defn service-worker-message-handler [^clj conn msg]
  [:chrome/service-worker-port :string => :any]
  (let [vconfig          (.-vconfig conn)
        port             (.-port conn)
        {:keys [async-processor active-requests]} @vconfig
        decoded-message  (enc/catching (encode/read msg))
        request-id       (mk/request-id decoded-message)
        target-id        (mk/target-id decoded-message)
        request          (mk/request decoded-message)
        response-channel (when request-id
                           (get active-requests request-id))]
    (if response-channel
      (async/go
        (vswap! vconfig update :active-requests dissoc request-id)
        (async/>! response-channel (or decoded-message {mk/error "Message empty or decode failed."}))
        (async/close! response-channel))
      (async/go
        (if (some? (mk/connected? decoded-message))
          (let [connected? (mk/connected? decoded-message)]
            (vswap! vconfig assoc :connected? connected?)
            (async-processor [(bi/devtool-connected {:connected? connected?})]))
          (when (and request-id request)
            (enc/try*
              (let [result (async/<! (async-processor request))]
                (if (map? result)
                  (post-message! port (encode/write
                                        {mk/request-id request-id
                                         mk/target-id  target-id
                                         mk/response   result}))
                  (post-message! port (encode/write
                                        {mk/request-id request-id
                                         mk/target-id  target-id
                                         mk/error      (ex-message result)}))))
              (catch :any e
                (log/error e "Failed to handle incoming request")
                (post-message! port (encode/write
                                      {mk/error     (ex-message e)
                                       mk/target-id target-id}))))))))))

(deftype ChromeExtensionConnection [port vconfig]
  dp/DevToolConnection
  (-transmit! [this target-id edn]
    (let [connected? (get @vconfig :connected?)]
      (if connected?
        (let [request-id       (random-uuid)
              response-channel (async/chan)
              packet           {mk/request-id request-id
                                mk/target-id  target-id
                                mk/request    edn}]
          (vswap! vconfig assoc-in [:active-requests request-id] response-channel)
          (post-message! port (encode/write packet))
          (async/go
            (async/>! send-ch packet)
            (let [timer (async/timeout 10000)
                  [result c] (async/alts! [response-channel timer] :priority true)]
              (if (= c response-channel)
                result
                {mk/error "Timed out"}))))
        (async/go {mk/error "Not connected"})))))

(defn new-chrome-extension-connection
  "Creates a DevtoolConnection from the Chrome dev tool extension itself. In other words, this is used by your devtool.
   in order to construct a devtool-remote."
  [async-processor]
  (let [port-name (str constants/devtool-port-name ":" current-tab-id)
        port      (runtime-connect! port-name)
        send-ch   (async/chan (async/dropping-buffer 10000))
        conn      (ct/->Connection port (volatile! {:connected?      false
                                                                :send-ch send-ch
                                                                :async-processor async-processor
                                                                :active-requests {}}))]
    (add-on-message-listener! port (partial service-worker-message-handler conn))
    conn))

(defn add-devtool-remote! [app]
  (let [c    (atom nil)
        conn (new-chrome-extension-connection
               (fn [msg] (resolvers/process-async-request {:devtool/connection @c
                                                           :fulcro/app         app} msg)))]
    (reset! c conn)
    (app/set-remote! app :devtool-remote (fdr/devtool-remote conn))
    app))
