(ns devtool.ui
  (:require
    [com.fulcrologic.fulcro.components :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]))

(defsc Root [this props]
  {:query         [:ui/foo]
   :initial-state {:ui/foo 1}}
  (dom/div "Tool UI"))
