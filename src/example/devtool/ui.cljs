(ns devtool.ui
  (:require
    [cljs.pprint :refer [pprint]]
    [clojure.edn :as edn]
    [com.fulcrologic.devtools.chrome.devtool :refer [active-target-descriptors]]
    [com.fulcrologic.devtools.message-keys :as mk]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [common.tooling :as tooling]
    [taoensso.timbre :as log]))

(defmutation counter-added [{:tool/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:tool/id id :ui/counters] inc)))

(defn target-message-received [app {:keys [tx action] :as msg}]
  (log/info "PUSH message from target" msg)
  (let [target-id (mk/target-id msg)]
    (cond
      ;; Explicit action
      (= action 'target-app.main/add-counter) (comp/transact! app [(counter-added {:tool/id target-id})]
                                                {:ref [:tool/id target-id]})
      ;; Mutation (use declare-mutation from the app side)
      tx (comp/transact! app tx {:ref [:tool/id target-id]}))))

(defsc Tool [this {:tool/keys [id label]
                   :ui/keys   [counter-sum counters]}]
  {:query         [:tool/id
                   :tool/label
                   :ui/counter-sum
                   :ui/counters]
   :ident         :tool/id
   :initial-state {:tool/id        :param/id
                   :tool/label     :param/label
                   :ui/counter-sum 0
                   :ui/counters    0}}
  (dom/div
    (dom/h3 (str label))
    (dom/button
      {:onClick (fn [] (comp/transact! this [(tooling/restart {mk/target-id id})]))}
      "RESET")
    (dom/div
      (str "Counters: " counters))
    (dom/div
      (str "Counter Sum: " counter-sum))))

(def ui-tool (comp/factory Tool {:keyfn :tool/id}))

(m/defmutation set-active-tool [{:tool/keys [id label] :as tool}]
  (action [{:keys [state]}]
    (when id
      (if (get-in @state [:tool/id id :tool/id])
        (swap! state assoc :ui/active-tool [:tool/id id])
        (swap! state merge/merge-component Tool tool :replace [:ui/active-tool])))))

(defsc Root [this {:devtool/keys [active-targets]
                   :ui/keys [active-tool] :as props}]
  {:query         [:devtool/active-targets
                   {:ui/active-tool (comp/get-query Tool)}]
   :initial-state {}}
  (dom/div nil
    (dom/h4 nil "Tool UI")
    (dom/select {:value    (pr-str (:tool/id active-tool))
                 :onChange (fn [evt]
                             (let [id    (log/spy :info (edn/read-string (evt/target-value evt)))
                                   label (get @active-target-descriptors id)]
                               (comp/transact! this [(set-active-tool {:tool/id    id
                                                                       :tool/label label})])))}
      (dom/option {:value "nil"} "Select an app")
      (mapv
        (fn [tid] (dom/option {:key   (str tid)
                               :value (pr-str tid)}
                    (get active-targets tid (str "Target " tid))))
        (keys active-targets)))
    (when active-tool
      (ui-tool active-tool))))
