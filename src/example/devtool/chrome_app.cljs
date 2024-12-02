(ns devtool.chrome-app
  (:require
    [com.fulcrologic.devtools.chrome.devtool :as dt]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.fulcro.components]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [devtool.ui :as ui]
    [taoensso.timbre :as log]))

(defn target-message-received [msg]
  (log/info "Target application sent an out-of-band-message" msg))

(defn start! []
  (log/info "Starting devtool")
  (let [app  (with-react18
               (app/fulcro-app
                 {:remotes {:remote (dt/devtool-remote target-message-received)}}))
        node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    (app/mount! app ui/Root node)
    app))

(defonce started (start!))
