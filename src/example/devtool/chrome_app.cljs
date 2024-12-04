(ns devtool.chrome-app
  (:require
    [com.fulcrologic.devtools.chrome.devtool :as tool]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.application]
    [com.fulcrologic.fulcro.components]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [devtool.ui :as ui]
    [taoensso.timbre :as log]))

(defn start! []
  (log/info "Starting devtool")
  (let [app (with-react18 (app/fulcro-app))]
    (tool/add-devtool-remote! app)
    (app/mount! app ui/Root "app")
    app))

(defonce started (start!))
