(ns common.target-impl
  (:require
    [com.fulcrologic.devtools.common.resolvers :as res]
    [com.fulcrologic.devtools.common.target :refer [ido]]
    [com.fulcrologic.fulcro.algorithms.normalize :as fnorm]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.wsscode.pathom.connect :as pc]
    [common.target-api :as api]))

(ido
  (res/defmutation restart [{:fulcro/keys [app]} input]
    {::pc/sym `api/restart}
    (let [Root          (comp/react-type (app/app-root app))
          initial-state (comp/get-initial-state Root {})
          state-atom    (::app/state-atom app)
          pristine-db   (fnorm/tree->db Root initial-state true)]
      (reset! state-atom pristine-db)
      (app/force-root-render! app))
    nil)

  (res/defresolver counter-stats-resolver [{:fulcro/keys [app]} input]
    {::pc/output [{:counter/stats [:stats/number-of-counters
                                   :stats/sum-of-counters]}]}
    (let [state-map (app/current-state app)
          counters  (vals (:counter/id state-map))]
      {:counter/stats
       {:stats/number-of-counters (count counters)
        :stats/sum-of-counters    (reduce + 0 (map :counter/n counters))}})))
