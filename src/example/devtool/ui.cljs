(ns devtool.ui
  (:require
    [clojure.edn :as edn]
    [com.fulcrologic.devtools.common.devtool-default-mutations :refer [Target]]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.devtool-io :as dev]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [common.target-api :as tapi]
    [common.tool-impl]
    [taoensso.timbre :as log]))

(defmutation counter-added [{:tool/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:tool/id id :ui/counters] inc)))

(defsc CounterStats [this {:stats/keys [number-of-counters
                                        sum-of-counters]}]
  {:query [:stats/number-of-counters
           :stats/sum-of-counters]}
  (dom/div
    (dom/div
      "Counters: " number-of-counters)
    (dom/div
      "Sum: " sum-of-counters)))

(def ui-counter-stats (comp/factory CounterStats))

(defsc Tool [this {:tool/keys [id label counter-stats]}]
  {:query         [:tool/id
                   :tool/label
                   {:tool/counter-stats (comp/get-query CounterStats)}]
   :ident         :tool/id
   :initial-state {:tool/id            :param/id
                   :tool/label         :param/label
                   :tool/counter-stats {}}}
  (dom/div
    (dom/h3 (str label))
    (dom/button
      {:onClick (fn [] (dev/transact! this id [(tapi/restart {})]))}
      "RESET")
    (dom/button {:onClick (fn []
                            (dev/load! this id :counter/stats CounterStats
                              {:target [:tool/id id :tool/counter-stats]}))}
      "Load counter stats")
    (when counter-stats
      (ui-counter-stats counter-stats))))

(def ui-tool (comp/factory Tool {:keyfn :tool/id}))

(m/defmutation set-active-tool [{:tool/keys [id label] :as tool}]
  (action [{:keys [state]}]
    (when id
      (if (get-in @state [:tool/id id :tool/id])
        (swap! state assoc :ui/active-tool [:tool/id id])
        (swap! state merge/merge-component Tool tool :replace [:ui/active-tool])))))

(defsc Root [this {:devtool/keys [active-targets]
                   :ui/keys      [active-tool] :as props}]
  {:query         [{:devtool/active-targets (comp/get-query Target)}
                   {:ui/active-tool (comp/get-query Tool)}]
   :initial-state {:devtool/active-targets []}}
  (let [state-map (app/current-state this)]
    (dom/div nil
      (dom/h4 nil "Tool UI")
      (dom/select {:value    (pr-str (:tool/id active-tool))
                   :onChange (fn [evt]
                               (let [id    (log/spy :info (edn/read-string (evt/target-value evt)))
                                     label (get-in state-map [mk/target-id id mk/target-description])]
                                 (comp/transact! this [(set-active-tool {:tool/id    id
                                                                         :tool/label label})])))}
        (dom/option {:value "nil"} "Select an app")
        (mapv (fn [{::mk/keys [target-id target-description]}]
                (dom/option {:key   (str target-id)
                             :value (pr-str target-id)}
                  (str target-description))) active-targets))
      (when active-tool
        (ui-tool active-tool)))))
