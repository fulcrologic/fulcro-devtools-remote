(ns com.fulcrologic.devtools.common.devtool-default-mutations
  "Require this ns to get a default implementation for target-started, and devtool-connected. Manages the lst
   of active targets at the root of the db at :devtool/active-targets. See Target in this ns for a sample component."
  (:require
    [com.fulcrologic.devtools.common.resolvers :as resolvers]
    [com.fulcrologic.devtools.common.message-keys :as mk]
    [com.fulcrologic.devtools.common.built-in-mutations :as bi]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]))

(defsc Target [this {::mk/keys [target-id target-description] :as params}]
  {:query [::mk/target-id ::mk/target-description]
   :ident ::mk/target-id})

(defmutation target-started [params]
  (action [{:keys [state]}]
    (log/spy :info params)
    (swap! state merge/merge-component Target params :append [:devtool/active-targets])))

(defmutation clear-active-targets [_]
  (action [{:keys [state]}]
    (swap! state assoc :devtool/active-targets [])))

(resolvers/defmutation devtool-connected [{:fulcro/keys [app]} {:keys [connected?] :as params}]
  {::pc/sym `bi/devtool-connected}
  (log/spy :info params)
  (when-not connected?
    (comp/transact! app [(clear-active-targets {})])))

(resolvers/defmutation target-started-handler [{:fulcro/keys [app]} {::mk/keys [target-id] :as params}]
  {::pc/sym `bi/target-started}
  (comp/transact! app [(target-started params)])
  {})

