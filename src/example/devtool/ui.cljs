(ns devtool.ui
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [common.tooling :refer [restart]]))

(defsc Root [this props]
  {:query         [:ui/foo]
   :initial-state {:ui/foo 1}}
  (dom/div (dom/h4 "Tool UI")
    (dom/button {:onClick (fn []
                            (comp/transact! this [(restart {})]))}
      "Click me!!!")))
