(ns maestro.core
  (:refer-clojure :exclude [compile])
  (:require
   [sci.core :as sci])
  (:import java.util.concurrent.ArrayBlockingQueue))

(defn- elapsed-ms [start]
  (/ (double (- (System/nanoTime) start)) 1000000.0))

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
        (catch Exception ex
          (error-handler
           (ex-info "execution error" {:current-state-id current-state-id
                                       :data             data
                                       :error            ex})))))))

(defn compile-state-handler
  [state-id {:keys [handler dispatches async?]} ctx valid-dispatch-targets]
  {:handler (normalize-handler state-id handler async?)
   :dispatches      (for [[target handler] dispatches]
                      (if-not (contains? valid-dispatch-targets target)
                        (throw
                         (ex-info (str "invalid dispatch " target " for state " state-id)
                                  {:id     state-id
                                   :target target}))
                        [target (sci/eval-form ctx handler)]))})

(defn validate-state-spec [id {:keys [handler dispatches] :as spec}]
  (when (nil? handler)
    (throw (ex-info (str "missing handler for spec " id) {:id id :spec spec})))
  (when (and (not (contains? #{::end ::halt ::error} id)) (nil? dispatches))
    (throw (ex-info (str "missing dispatches for spec " id) {:id id :spec spec}))))

(defn compile-dispatches [spec]
  (let [ctx (sci/init {})
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

(defn enqueue-next-state [queue {:keys [current-state-id opts] :as fsm} resources dispatches post error-handler start-time data]
  (let [target-id (ffirst (drop-while (fn [[_target selector]] (not (selector data))) dispatches))]
    (if (get-in fsm [:fsm target-id])
      (.put queue (-> fsm
                      (update :trace add-trace-segment
                              (:max-trace opts)
                              {:state-id    current-state-id
                               :status      :success
                               :duration-ms (elapsed-ms start-time)})
                      (assoc :current-state-id target-id
                             :last-state-id current-state-id
                             :data data)
                      (post resources)))
      (error-handler
       (ex-info "invalid target state transition" {:current-state-id current-state-id
                                                   :target-state-id  target-id})))))

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
         error (get-in spec [:fsm ::error :handler] default-on-error)]
     (-> spec
         (update :fsm dissoc ::end ::error)
         (compile-dispatches)
         (update :fsm merge {::end {:handler end}
                             ::halt {:handler (fn [_resources fsm] (dissoc fsm :fsm))}
                             ::error {:handler error}})))))

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

(defn run
  "executes the FSM spec compiled using compile"
  ([fsm]
   (run fsm {} {}))
  ([fsm resources]
   (run fsm resources {}))
  ([{fsm :fsm
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
   (let [queue (ArrayBlockingQueue. 1)]
     (.put queue
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
     (loop [{:keys [data current-state-id last-state-id opts] :as fsm} (pre (.take queue) resources)]
       (let [{:keys [handler dispatches]} (get-in fsm [:fsm current-state-id])
             fsm (assoc-in fsm [:opts :subscriptions] (run-subscriptions! data (:subscriptions opts)))
             start-time (System/nanoTime)
             error-callback (fn [error]
                              (.put queue
                                    (-> (update fsm :trace add-trace-segment
                                                max-trace
                                                {:state-id    current-state-id
                                                 :status      :error
                                                 :duration-ms (elapsed-ms start-time)})
                                        (assoc :current-state-id ::error :error error))))
             callback (partial enqueue-next-state queue fsm resources dispatches post error-callback start-time)]
         (cond
           (= ::end current-state-id)
           (handler resources fsm)

           (= ::halt current-state-id)
           (handler resources (assoc fsm :current-state-id last-state-id :last-state-id nil))

           (= ::error current-state-id)
           (handler resources fsm)

           :else
           (do
             (handler resources data callback error-callback)
             (recur (pre (.take queue) resources)))))))))

(defn run-async
  "executes the FSM asynchronously, returns a future with the result"
  ([fsm]
   (future (run fsm)))
  ([fsm resources]
   (future (run fsm resources)))
  ([fsm resources state]
   (future (run fsm resources state))))

(defn analyze
  "analyzes an FSM spec and returns a map with:
   :reachable      - set of states reachable from ::start
   :unreachable    - set of states not reachable from ::start
   :no-path-to-end - set of reachable states with no path to ::end
   :cycles         - collection of cycles found in the FSM"
  [{:keys [fsm]}]
  (let [state-ids (set (keys fsm))
        ;; Build adjacency map: state -> set of dispatch targets
        edges (reduce-kv
               (fn [m state-id {:keys [dispatches]}]
                 (assoc m state-id
                        (into #{} (map first) dispatches)))
               {}
               fsm)
        ;; BFS from ::start to find reachable states
        reachable (loop [visited #{}
                         queue [::start]]
                    (if (empty? queue)
                      visited
                      (let [state (first queue)
                            queue (subvec queue 1)]
                        (if (or (contains? visited state)
                                (not (contains? state-ids state)))
                          (recur visited queue)
                          (recur (conj visited state)
                                 (into queue (get edges state)))))))
        ;; Unreachable = declared states minus reachable (excluding terminal states)
        unreachable (disj (clojure.set/difference state-ids reachable)
                          ::end ::halt ::error)
        ;; Reverse edges for backward reachability from ::end
        reverse-edges (reduce-kv
                       (fn [m state-id targets]
                         (reduce (fn [m target]
                                   (update m target (fnil conj #{}) state-id))
                                 m
                                 targets))
                       {}
                       edges)
        ;; BFS backward from ::end to find states that can reach ::end
        can-reach-end (loop [visited #{}
                             queue [::end]]
                        (if (empty? queue)
                          visited
                          (let [state (first queue)
                                queue (subvec queue 1)]
                            (if (contains? visited state)
                              (recur visited queue)
                              (recur (conj visited state)
                                     (into queue (get reverse-edges state)))))))
        ;; Reachable states that cannot reach ::end (excluding terminal states)
        no-path-to-end (disj (clojure.set/difference reachable can-reach-end)
                             ::start ::end ::halt ::error)
        ;; Find cycles using DFS with coloring
        cycles (let [find-cycles
                     (fn find-cycles [node visited path path-set cycles]
                       (if (contains? path-set node)
                         ;; Found a cycle - extract it
                         (let [cycle-start (.indexOf path node)
                               cycle (subvec path cycle-start)]
                           (conj cycles cycle))
                         (if (contains? visited node)
                           cycles
                           (let [visited (conj visited node)
                                 path (conj path node)
                                 path-set (conj path-set node)]
                             (reduce
                              (fn [cycles neighbor]
                                (if (contains? state-ids neighbor)
                                  (find-cycles neighbor visited path path-set cycles)
                                  cycles))
                              cycles
                              (get edges node))))))]
                 (reduce
                  (fn [cycles state-id]
                    (find-cycles state-id #{} [] #{} cycles))
                  []
                  reachable))]
    {:reachable      reachable
     :unreachable    unreachable
     :no-path-to-end no-path-to-end
     :cycles         cycles}))
