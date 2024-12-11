(ns target-app.main
  (:require
    [com.fulcrologic.devtools.common.target :as dt]
    [com.fulcrologic.devtools.devtool-io :as dev]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [common.target-impl]
    [common.tool-api :as tapi]))

(defonce app1 (with-react18 (app/fulcro-app)))
(defonce app2 (with-react18 (app/fulcro-app)))

(defn devtool-target-id
  "In your target application, you have to keep track of the ID you were assigned on target-started, and send that
   to the server for push. If your target app isn't Fulcro, invent this storage for yourself."
  [app] (some-> app ::app/runtime-atom deref ::id))

(defsc Counter [this {:counter/keys [n]}]
  {:ident         :counter/id
   :query         [:counter/id :counter/n]
   :initial-state {:counter/id :param/id
                   :counter/n  0}}
  (dom/li nil
    (dom/button {:onClick (fn [] (m/set-integer!! this :counter/n :value (inc n)))} (str n))))

(def ui-counter (comp/factory Counter {:keyfn :counter/id}))

(defn add-counter* [state-map id]
  (-> state-map
    (merge/merge-component Counter {:counter/id id
                                    :counter/n  0}
      :append [:ui/counters])))

(defmutation add-counter [_]
  (action [{:keys [app state]}]
    (let [next-id (inc (reduce max 0 (mapv :counter/id (vals (:counter/id @state)))))]
      (dt/ido
        (dev/transact! app (::app/id app) [(tapi/counter-added {})]))
      (swap! state add-counter* next-id))))

(defsc Root [this {:ui/keys [counters]}]
  {:query         [{:ui/counters (comp/get-query Counter)}]
   :initial-state {:ui/counters [{:id 1}]}}
  (dom/div nil
    (dom/h4 nil "Counters")
    (mapv ui-counter counters)
    (dom/button {:onClick (fn [] (comp/transact! this [(add-counter {})]))}
      "New Counter")))

(defn refresh []
  (app/mount! app1 Root "app1")
  #_(app/mount! app2 Root "app2"))

(defn start []
  (refresh)
  (dt/ido
    (dt/add-devtool-remote! app1 "App 1")
    #_(dt/add-devtool-remote! app2 "App 2")))

(start)
