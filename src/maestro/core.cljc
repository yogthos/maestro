(ns maestro.core
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.set :as set]
   [sci.core :as sci]
   [promesa.core :as p]))

(defn default-on-end
  "returns the data key from the FSM map"
  [_resources {:keys [data]}]
  data)

(defn default-on-error
  "default error handler throws ex-info
   with original error being found under the :error key"
  [_resources fsm]
  (throw (ex-info "execution error" fsm)))

(defn normalize-handler
  [current-state-id handler async?]
  (if async?
    handler
    (fn [resources data callback error-handler]
      (try
        (callback (handler resources data))
        (catch #?(:clj Exception :cljs :default) ex
          (error-handler
           (ex-info "execution error" {:current-state-id current-state-id
                                       :data             data
                                       :error            ex})))))))

(defn- validate-dispatch-targets [state-id dispatches valid-dispatch-targets]
  (doseq [[target _] dispatches]
    (when-not (contains? valid-dispatch-targets target)
      (throw
       (ex-info (str "invalid dispatch " target " for state " state-id)
                {:id     state-id
                 :target target})))))

(defn- compile-dispatch-pred
  "Compiles a dispatch predicate. If it's already an IFn (inline spec),
   uses it directly. Otherwise evaluates it as a SCI form (EDN spec)."
  [ctx pred]
  (if (ifn? pred) pred (sci/eval-form @ctx pred)))

(defn- compile-state-handler
  [state-id {:keys [handler dispatches async?]} ctx valid-dispatch-targets]
  (validate-dispatch-targets state-id dispatches valid-dispatch-targets)
  {:handler    (normalize-handler state-id handler async?)
   :dispatches (mapv (fn [[target pred]]
                       [target (compile-dispatch-pred ctx pred)])
                     dispatches)})

(defn- validate-state-spec [id {:keys [handler dispatches] :as spec}]
  (when (nil? handler)
    (throw (ex-info (str "missing handler for spec " id) {:id id :spec spec})))
  (when (and (not (contains? #{::end ::halt ::error} id)) (nil? dispatches))
    (throw (ex-info (str "missing dispatches for spec " id) {:id id :spec spec}))))

(defn- compile-dispatches [spec]
  (let [ctx (delay (sci/init {}))
        valid-dispatch-targets (-> spec :fsm keys set (conj ::end ::halt ::error))]
    (update spec :fsm
            (fn [fsm]
              (reduce
               (fn [fsm [id state-spec]]
                 (validate-state-spec id state-spec)
                 (assoc fsm id (compile-state-handler id state-spec ctx valid-dispatch-targets)))
               {}
               fsm)))))

(defn add-trace-segment [trace max-trace segment]
  (vec (take-last max-trace (conj trace segment))))

(defn- resolve-next-state
  "Resolves the next FSM state given dispatches and data.
   Returns the updated FSM map or throws if no dispatch matches."
  [{:keys [current-state-id opts] :as fsm} resources dispatches post data]
  (let [target-id (ffirst (drop-while (fn [[_target selector]] (not (selector data))) dispatches))]
    (if (get-in fsm [:fsm target-id])
      (-> fsm
          (update :trace add-trace-segment
                  (:max-trace opts)
                  {:state-id current-state-id
                   :status   :success})
          (assoc :current-state-id target-id
                 :last-state-id current-state-id
                 :data data)
          (post resources))
      (throw (ex-info "invalid target state transition" {:current-state-id current-state-id
                                                         :target-state-id  target-id})))))

(defn run-subscriptions! [data subscriptions]
  (when subscriptions
    (reduce
     (fn [m [path {:keys [value handler] :as sub}]]
       (let [new-value (get-in data path)]
         (if (= new-value value)
           (assoc m path sub)
           (do
             (handler path value new-value)
             (assoc m path (assoc sub :value new-value))))))
     {}
     subscriptions)))

(defn compile
  "compiles the FSM from the spec, compiled FSM should be passed to the run function"
  ([spec handlers]
   (compile
    (update spec :fsm
            (fn [fsm]
              (reduce
               (fn [fsm [k {:keys [handler] :as v}]]
                 (let [handler-fn (get handlers handler)]
                   (when-not handler-fn
                     (throw (ex-info (str "no handler found for state " k " handler id " handler)
                                     {:state v
                                      :handlers handlers})))
                   (assoc fsm k (assoc v :handler handler-fn))))
               {}
               fsm)))))
  ([spec]
   (let [end (get-in spec [:fsm ::end :handler] default-on-end)
         error (get-in spec [:fsm ::error :handler] default-on-error)
         has-async? (some (fn [[_id state-spec]] (:async? state-spec))
                          (dissoc (:fsm spec) ::end ::error))]
     (-> spec
         (update :fsm dissoc ::end ::error)
         (compile-dispatches)
         (update :fsm merge {::end {:handler end}
                             ::halt {:handler (fn [_resources fsm] (dissoc fsm :fsm))}
                             ::error {:handler error}})
         (cond-> has-async? (assoc :has-async? true))))))

(defn- make-initial-fsm
  "Creates the initial FSM state map."
  [fsm max-trace subscriptions post resources current-state-id trace data]
  (post
   {:fsm              fsm
    :current-state-id current-state-id
    :last-state-id    (:state-id (last trace))
    :data             data
    :trace            trace
    :opts             {:max-trace     max-trace
                       :subscriptions (reduce
                                       (fn [m [path sub]]
                                         (assoc m path (assoc sub :value (get-in data path))))
                                       {}
                                       subscriptions)}}
   resources))

(defn- run-sync
  "Synchronous FSM execution using loop/recur. Works on both CLJ and CLJS.
   Used when no async handlers are present."
  [fsm-map max-trace subscriptions pre post resources current-state-id trace data]
  (let [initial (make-initial-fsm fsm-map max-trace subscriptions post resources
                                  current-state-id trace data)]
    (loop [{:keys [data current-state-id last-state-id opts] :as fsm} (pre initial resources)]
      (let [{:keys [handler dispatches]} (get-in fsm [:fsm current-state-id])
            fsm (assoc-in fsm [:opts :subscriptions] (run-subscriptions! data (:subscriptions opts)))]
        (cond
          (= ::end current-state-id)
          (handler resources fsm)

          (= ::halt current-state-id)
          (handler resources (assoc fsm :current-state-id last-state-id :last-state-id nil))

          (= ::error current-state-id)
          (handler resources fsm)

          :else
          (let [next-state (volatile! nil)
                error-state (volatile! nil)
                callback (fn [result-data]
                           (try
                             (vreset! next-state
                                      (resolve-next-state fsm resources dispatches post result-data))
                             (catch #?(:clj Exception :cljs :default) e
                               (vreset! error-state
                                        (-> (update fsm :trace add-trace-segment
                                                    max-trace
                                                    {:state-id current-state-id
                                                     :status   :error})
                                            (assoc :current-state-id ::error :error e))))))
                error-callback (fn [error]
                                 (vreset! error-state
                                          (-> (update fsm :trace add-trace-segment
                                                      max-trace
                                                      {:state-id current-state-id
                                                       :status   :error})
                                              (assoc :current-state-id ::error :error error))))]
            (handler resources data callback error-callback)
            (if-let [err @error-state]
              (recur (pre err resources))
              (recur (pre @next-state resources)))))))))

(defn- run-async
  "Async FSM execution using Promesa. Works on both CLJ (CompletableFuture)
   and CLJS (JS Promise). Returns a promise that resolves to the final result."
  [fsm-map max-trace subscriptions pre post resources current-state-id trace data]
  (let [initial (make-initial-fsm fsm-map max-trace subscriptions post resources
                                  current-state-id trace data)
        step (fn step [{:keys [data current-state-id last-state-id opts] :as fsm}]
               (let [{:keys [handler dispatches]} (get-in fsm [:fsm current-state-id])
                     fsm (assoc-in fsm [:opts :subscriptions]
                                   (run-subscriptions! data (:subscriptions opts)))]
                 (cond
                   (= ::end current-state-id)
                   (p/resolved (handler resources fsm))

                   (= ::halt current-state-id)
                   (p/resolved (handler resources (assoc fsm :current-state-id last-state-id
                                                         :last-state-id nil)))

                   (= ::error current-state-id)
                   (p/resolved (handler resources fsm))

                   :else
                   (let [d (p/deferred)]
                     (let [callback (fn [result-data]
                                      (try
                                        (let [next (resolve-next-state fsm resources dispatches
                                                                       post result-data)]
                                          (p/resolve! d next))
                                        (catch #?(:clj Exception :cljs :default) e
                                          (p/resolve! d
                                                      (-> (update fsm :trace add-trace-segment
                                                                  max-trace
                                                                  {:state-id current-state-id
                                                                   :status   :error})
                                                          (assoc :current-state-id ::error :error e))))))
                           error-callback (fn [error]
                                            (p/resolve! d
                                                        (-> (update fsm :trace add-trace-segment
                                                                    max-trace
                                                                    {:state-id current-state-id
                                                                     :status   :error})
                                                            (assoc :current-state-id ::error
                                                                   :error error))))]
                       (handler resources data callback error-callback))
                     (p/then d (fn [next-fsm]
                                 (step (pre next-fsm resources))))))))]
    (step (pre initial resources))))

(defn run
  "Executes the FSM spec compiled using compile.
   For sync-only workflows, returns the result directly.
   For workflows with async handlers, returns a promise (deref-able on JVM)."
  ([fsm]
   (run fsm {} {}))
  ([fsm resources]
   (run fsm resources {}))
  ([{fsm-map :fsm
     has-async? :has-async?
     {:keys [max-trace subscriptions pre post]
      :or {max-trace 1000
           pre (fn [fsm _resources] fsm)
           post (fn [fsm _resources] fsm)}} :opts}
    resources
    {trace :trace
     data :data
     current-state-id :current-state-id
     :or {current-state-id ::start
          trace []
          data {}}}]
   (if has-async?
     (run-async fsm-map max-trace subscriptions pre post resources
                current-state-id trace data)
     (run-sync fsm-map max-trace subscriptions pre post resources
               current-state-id trace data))))

(defn- bfs
  "Breadth-first search from start nodes using adjacency map.
   Returns the set of all visited nodes."
  [start-nodes adjacency]
  (loop [visited #{}
         queue (vec start-nodes)]
    (if (empty? queue)
      visited
      (let [node (first queue)
            queue (subvec queue 1)]
        (if (contains? visited node)
          (recur visited queue)
          (recur (conj visited node)
                 (into queue (get adjacency node))))))))

(defn- reverse-graph
  "Reverse edges of an adjacency map."
  [edges]
  (reduce-kv
   (fn [m state-id targets]
     (reduce (fn [m target]
               (update m target (fnil conj #{}) state-id))
             m
             targets))
   {}
   edges))

(defn- find-cycles
  "Find all unique cycles reachable from start-nodes in the graph.
   Returns a vector of cycles, each cycle being a vector of state-ids."
  [start-nodes edges valid-nodes]
  (let [normalize-cycle (fn [c]
                          (let [min-el (first (sort-by str c))
                                min-idx (.indexOf c min-el)]
                            (vec (concat (subvec c min-idx) (subvec c 0 min-idx)))))
        dfs (fn dfs [node path path-set found]
              (if (contains? path-set node)
                (let [cycle-start (.indexOf path node)
                      cycle (normalize-cycle (subvec path cycle-start))]
                  (conj found cycle))
                (reduce
                 (fn [found neighbor]
                   (if (contains? valid-nodes neighbor)
                     (dfs neighbor (conj path node) (conj path-set node) found)
                     found))
                 found
                 (get edges node))))]
    (->> (reduce
          (fn [found state-id]
            (dfs state-id [] #{} found))
          #{}
          start-nodes)
         vec)))

(defn analyze
  "analyzes an FSM spec and returns a map with:
   :reachable      - set of states reachable from ::start
   :unreachable    - set of states not reachable from ::start
   :no-path-to-end - set of reachable states with no path to ::end
   :cycles         - collection of cycles found in the FSM"
  [{:keys [fsm]}]
  (let [state-ids (set (keys fsm))
        edges (reduce-kv
               (fn [m state-id {:keys [dispatches]}]
                 (assoc m state-id (into #{} (map first) dispatches)))
               {}
               fsm)
        reachable (bfs [::start] edges)
        reachable (set/intersection reachable state-ids)
        unreachable (disj (set/difference state-ids reachable)
                          ::end ::halt ::error)
        can-reach-end (bfs [::end] (reverse-graph edges))
        no-path-to-end (disj (set/difference reachable can-reach-end)
                             ::start ::end ::halt ::error)
        cycles (find-cycles reachable edges state-ids)]
    {:reachable      reachable
     :unreachable    unreachable
     :no-path-to-end no-path-to-end
     :cycles         cycles}))
