(ns devtool.chrome-app
  (:require
    [com.fulcrologic.devtools.chrome.devtool :as dt]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.fulcro.components]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [devtool.ui :as ui]
    [taoensso.timbre :as log]))

(defn start! []
  (log/info "Starting devtool")
  (let [aa   (atom nil)
        app  (with-react18
               (app/fulcro-app
                 {:remotes {:remote (dt/devtool-remote (fn [msg] (ui/target-message-received @aa msg)))}}))
        _    (reset! aa app)
        node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    (app/mount! app ui/Root node)
    (add-watch dt/active-target-descriptors :at (fn [_ _ _ new] (app/force-root-render! app)))
    app)
  )

(defonce started (start!))
