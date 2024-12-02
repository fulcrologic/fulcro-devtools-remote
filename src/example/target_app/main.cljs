(ns target-app.main
  (:require
    [com.fulcrologic.devtools.chrome.target :as t]
    [com.fulcrologic.devtools.target :as dt]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.react.version18 :refer [with-react18]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]))

(declare tooling-processor parser)
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
        ;; Tell the dev tool that I added a counter
        (t/push! (devtool-target-id app) {:action     `add-counter
                                          :counter/id next-id}))
      (swap! state add-counter* next-id))))

(defsc Root [this {:ui/keys [counters]}]
  {:query         [{:ui/counters (comp/get-query Counter)}]
   :initial-state {:ui/counters [{:id 1}]}}
  (dom/div nil
    (dom/h4 nil "Counters")
    (mapv ui-counter counters)
    (dom/button {:onClick (fn [] (comp/transact! this [(add-counter {})]))}
      "New Counter")))

(dt/ido
  ;; Allow the devtool to restart the application via a mutation
  (pc/defmutation restart [{:keys [app]} input]
    {::pc/sym 'common.tooling/restart}
    (let [initial-state (comp/get-initial-state Root {})
          state-atom    (::app/state-atom app)
          pristine-db   (fnorm/tree->db Root initial-state true)]
      (reset! state-atom pristine-db)
      (app/force-root-render! app)))

  (pc/defresolver counter-details-resolver [{:keys [app]} input]
    {::pc/output [{:target/counters [:counter/id :counter/n]}]}
    (let [state-map (app/current-state app)
          counters  (vals (:counter/id state-map))]
      {:target/counters (sort-by :counter/id counters)}))

  (defonce parser (p/async-parser {::p/env     {::p/reader [p/map-reader
                                                            pc/async-reader2
                                                            pc/open-ident-reader]}
                                   ::p/mutate  pc/mutate-async
                                   ::p/plugins [(pc/connect-plugin {::pc/register [restart counter-details-resolver]})]}))
  (defn tooling-processor [app EQL] (parser {:app app} EQL)))

(defn refresh []
  (app/mount! app1 Root "app1")
  (app/mount! app2 Root "app2"))

(defn start []
  (app/mount! app1 Root "app1")
  (app/mount! app2 Root "app2")
  (dt/ilet [{app1-rta ::app/runtime-atom} app1
            {app2-rta ::app/runtime-atom} app2]
    (swap! app1-rta assoc ::id (t/target-started! "App 1" (partial tooling-processor app1)))
    (swap! app2-rta assoc ::id (t/target-started! "App 2" (partial tooling-processor app2)))))

(start)
