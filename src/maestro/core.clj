(ns maestro.core
  (:refer-clojure :exclude [compile])
  (:require  
   [sci.core :as sci])
(:import java.util.concurrent.ArrayBlockingQueue))

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

(defn enqueue-next-state [queue {:keys [current-state-id opts] :as fsm} resources dispatches post error-handler data] 
  (let [target-id (ffirst (drop-while (fn [[_target selector]] (not (selector data))) dispatches))]
    (if (get-in fsm [:fsm target-id])
      (.put queue (-> fsm
                      (update :trace add-trace-segment
                              (:max-trace opts)
                              {:state-id current-state-id
                               :status   :success})
                      (assoc :current-state-id target-id 
                             :last-state-id current-state-id
                             :data data)
                      (post resources)))
      (error-handler
       resources
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
         (update :fsm dissoc ::end ::handler)
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
             :last-state-id    (last trace)
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
             error-callback (fn [error]
                              (.put queue
                                    (-> (update fsm :trace add-trace-segment
                                                max-trace
                                                {:current-state-id current-state-id
                                                 :status           :error})
                                        (assoc :current-state-id ::error :error error))))
             callback (partial enqueue-next-state queue fsm resources dispatches post error-handler)]
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
