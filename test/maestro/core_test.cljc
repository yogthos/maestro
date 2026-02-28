(ns maestro.core-test
  (:refer-clojure :exclude [compile])
  (:require
   #?(:clj  [clojure.test :refer [deftest testing is are]]
      :cljs [cljs.test :refer [deftest testing is are async]])
   #?(:clj  [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [maestro.core :as fsm]
   [promesa.core :as p]))

(deftest basic-fsm
  (->> (fsm/run
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :foo :bar))
                                         :dispatches [[:foo (constantly true)]]}
                            :foo       {:handler    (fn [_resources data] (assoc data :y 2))
                                        :dispatches [[::fsm/end (constantly true)]]}}}))
       (= {:foo :bar :y 2})
       (is)))

(deftest basic-fsm-resources
  (let [external-state (atom nil)]
    (->> (fsm/run
          (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :foo :bar))
                                           :dispatches [[:foo (constantly true)]]}
                              :foo       {:handler    (fn [{:keys [effector]} data]
                                                        (effector {:some :state})
                                                        (assoc data :y 2))
                                          :dispatches [[::fsm/end (constantly true)]]}}})
          {:effector (fn [v] (reset! external-state v))})
         (= {:foo :bar :y 2})
         (is))
    (is (= {:some :state} @external-state))))

(deftest basic-fsm-default-values
  (->> (fsm/run
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :foo :bar))
                                         :dispatches [[:foo (constantly true)]]}
                            :foo       {:handler    (fn [_resources data] (assoc data :y 2))
                                        :dispatches [[::fsm/end (constantly true)]]}}})
        {}
        {:data {:x 1}})
       (= {:x   1
           :foo :bar
           :y   2})
       (is)))

(deftest error
  (try
    (->> (fsm/run
          (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (throw (ex-info "Divide by zero" {})))
                                           :dispatches [[::fsm/end (constantly true)]]}}})))
    (is (= 1 0))
    (catch #?(:clj Exception :cljs :default) ex
      (is (= "execution error" (-> ex ex-data :error ex-message))))))

(deftest custom-error-handler
  (->> (fsm/run
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (throw (ex-info "Divide by zero" {})))
                                         :dispatches [[::fsm/end (constantly true)]]}
                            ::fsm/error {:handler (fn [_resources {:keys [error]}]
                                                    (-> error ex-data :error ex-message))}}}))
       (= "Divide by zero")
       (is)))

(deftest custom-end
  (->> (fsm/run
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :foo :bar))
                                         :dispatches [[:foo (fn [_state] true)]]}
                            :foo       {:handler    (fn [_resources data] (assoc data :y 2))
                                        :dispatches [[::fsm/end (constantly true)]]}
                            ::fsm/end   {:handler (fn [_resources state] (-> state :data :foo))}}}))
       (= :bar)
       (is)))

(deftest cycle-state
  (->> (fsm/run (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                               (update data :count (fnil inc 0)))
                                                 :dispatches [[::fsm/end (fn [state] (> (:count state) 3))]
                                                              [::fsm/start (constantly true)]]}}}))
       (= {:count 4})
       (is)))

(deftest async-test
  (let [spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                            (assoc data :foo :bar))
                                              :dispatches [[:foo (fn [_state] true)]]}
                                 :foo       {:handler    (fn [_resources data cb _error]
                                                           (cb (assoc data :x 1)))
                                             :async?     true
                                             :dispatches [[:bar (constantly true)]]}
                                 :bar       {:handler    (fn [_resources data] (assoc data :y 2))
                                             :dispatches [[::fsm/end (constantly true)]]}}})]
    #?(:clj
       (is (= {:foo :bar :x 1 :y 2} @(fsm/run spec)))
       :cljs
       (async done
              (-> (fsm/run spec)
                  (p/then (fn [result]
                            (is (= {:foo :bar :x 1 :y 2} result))
                            (done))))))))

(deftest async-error-test
  (let [spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                            (assoc data :foo :bar))
                                              :dispatches [[:foo (fn [_state] true)]]}
                                 :foo       {:handler    (fn [_resources _data _cb error]
                                                           (error (ex-info "error" {})))
                                             :async?     true
                                             :dispatches [[:bar (constantly true)]]}
                                 :bar       {:handler    (fn [_resources data] (assoc data :y 2))
                                             :dispatches [[::fsm/end (constantly true)]]}}})]
    #?(:clj
       (try
         @(fsm/run spec)
         (catch Exception ex
           (let [cause (or (ex-cause ex) ex)]
             (is (= (ex-message cause) "execution error")))))
       :cljs
       (async done
              (-> (fsm/run spec)
                  (p/catch (fn [ex]
                             (is (= (ex-message ex) "execution error"))
                             (done))))))))

(deftest edn-spec
  (let [spec (fsm/compile
              (edn/read-string
               #?(:clj  (slurp "test/fsm.edn")
                  :cljs "{:fsm {:maestro.core/start {:handler :foo :dispatches [[:maestro.core/end (fn [{:keys [v]}] (= v 5))]]}}}"))
              {:foo (fn [_resources data] (assoc data :v 5))})]
    (is (= {:v 5} (fsm/run spec)))))

(deftest halt-test
  (let [fsm (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                           (assoc data :foo :bar))
                                             :dispatches [[:foo (constantly true)]]}
                                :foo       {:handler    (fn [_resources data] (assoc data :y 2))
                                            :dispatches [[:bar (fn [data] (:ready? data))]
                                                         [::fsm/halt (constantly true)]]}
                                :bar       {:handler    (fn [_resources data] (assoc data :y 3))
                                            :dispatches [[::fsm/end (constantly true)]]}}})
        state (fsm/run fsm)]
    (is (= :foo (:current-state-id state)))
    (is (nil? (:last-state-id state)))
    (is (= {:foo :bar, :y 2} (:data state)))
    (is (= 1000 (get-in state [:opts :max-trace])))
    (is (= {} (get-in state [:opts :subscriptions])))
    (is (= [{:state-id :maestro.core/start, :status :success}
            {:state-id :foo, :status :success}]
           (mapv #(select-keys % [:state-id :status]) (:trace state))))
    (is (every? #(number? (:duration-ms %)) (:trace state)))
    (is (= {:foo    :bar
            :y      3
            :ready? true}
           (fsm/run fsm {} (assoc-in state [:data :ready?] true))))))

(deftest subscriptions-test
  (let [x    (atom nil)
        spec (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data]
                                                             (assoc data :foo :bar))
                                               :dispatches [[:foo (constantly true)]]}
                                  :foo       {:handler    (fn [_resources data]
                                                            (update-in data [:x :y] inc))
                                              :dispatches [[:bar (constantly true)]]}
                                  :bar       {:handler    (fn [_resources data cb _err] (cb (update-in data [:x :y] inc)))
                                              :async?     true
                                              :dispatches [[::fsm/end (constantly true)]]}}
                           :opts {:subscriptions {[:x :y] {:handler (fn [path old-value new-value]
                                                                      (reset! x {path [old-value new-value]}))}}}})]
    #?(:clj
       (do
         @(fsm/run spec {} {:data {:x {:y 1}}})
         (is (= @x {[:x :y] [2 3]})))
       :cljs
       (async done
              (-> (fsm/run spec {} {:data {:x {:y 1}}})
                  (p/then (fn [_]
                            (is (= @x {[:x :y] [2 3]}))
                            (done))))))))

(deftest pre-post-test
  (let [spec (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data]
                                                             (assoc data :foo :bar))
                                               :dispatches [[:foo (constantly true)]]}
                                  :foo       {:handler    (fn [_resources data] (assoc data :y 2))
                                              :dispatches [[:bar (constantly true)]]}
                                  :bar       {:handler    (fn [_resources data cb _err] (cb (assoc data :z 3)))
                                              :async?     true
                                              :dispatches [[::fsm/end (constantly true)]]}}
                           :opts {:pre  (fn [fsm _resources] (assoc-in fsm [:data :pre-value] 1))
                                  :post (fn [fsm _resources] (assoc-in fsm [:data :post-value] 2))}})]
    #?(:clj
       (is (= {:pre-value 1 :foo :bar :post-value 2 :y 2 :z 3}
              @(fsm/run spec)))
       :cljs
       (async done
              (-> (fsm/run spec)
                  (p/then (fn [result]
                            (is (= {:pre-value 1 :foo :bar :post-value 2 :y 2 :z 3} result))
                            (done))))))))

(deftest pre-post-sync-test
  (->>
   (fsm/run
    (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data] (update data :x inc))
                                      :dispatches [[:foo (constantly true)]]}
                         :foo       {:handler    (fn [_resources data] (update data :x inc))
                                     :dispatches [[::fsm/end (constantly true)]]}}
                  :opts {:pre  (fn [{:keys [current-state-id] :as fsm} _resources]
                                 (update-in fsm [:data :pre] (fnil conj [])
                                            {:pre current-state-id}))
                         :post (fn [{:keys [current-state-id] :as fsm} _resources]
                                 (update-in fsm [:data :post] (fnil conj [])
                                            {:post current-state-id}))}})
    {}
    {:data {:x 1}})
   (= {:x 3
       :post [{:post :maestro.core/start} {:post :foo} {:post :maestro.core/end}]
       :pre [{:pre :maestro.core/start} {:pre :foo} {:pre :maestro.core/end}]})
   (is)))

;; Tests for bug fixes

(deftest dispatch-error-handling
  (testing "error when no dispatch matches"
    (try
      (fsm/run
       (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                        :dispatches [[::fsm/end (constantly false)]]}}}))
      (is false "Should have thrown an error")
      (catch #?(:clj Exception :cljs :default) ex
        (is (= "execution error" (ex-message ex)))
        (let [original-error (-> ex ex-data :error)]
          (is (= "invalid target state transition" (ex-message original-error)))
          (is (= ::fsm/start (-> original-error ex-data :current-state-id)))
          (is (nil? (-> original-error ex-data :target-state-id))))))))

(deftest error-trace-consistency
  (testing "error traces use :state-id key"
    (let [result (fsm/run
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (throw (ex-info "test error" {})))
                                                   :dispatches [[::fsm/end (constantly true)]]}
                                      ::fsm/error {:handler (fn [_resources fsm] fsm)}}}))]
      (is (every? #(contains? % :state-id) (:trace result)))
      (is (not-any? #(contains? % :current-state-id) (:trace result))))))

(deftest last-state-id-type
  (testing "last-state-id is a keyword after resuming"
    (let [fsm-spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :step1 1))
                                                    :dispatches [[:middle (constantly true)]]}
                                       :middle     {:handler    (fn [_resources data] (assoc data :step2 2))
                                                    :dispatches [[::fsm/halt (constantly true)]]}
                                       :final      {:handler    (fn [_resources data] (assoc data :step3 3))
                                                    :dispatches [[::fsm/end (constantly true)]]}}})
          halted-state (fsm/run fsm-spec)]
      (is (nil? (:last-state-id halted-state)))
      (let [result (fsm/run fsm-spec {} (assoc halted-state
                                               :current-state-id :final
                                               :trace [{:state-id ::fsm/start :status :success}
                                                       {:state-id :middle :status :success}]))]
        (is (= 3 (:step3 result)))))))

(deftest last-state-id-from-trace
  (testing "last-state-id extracted from trace on resume"
    (let [captured (atom nil)
          fsm-spec (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data] data)
                                                     :dispatches [[::fsm/end (constantly true)]]}}
                                 :opts {:pre (fn [{:keys [last-state-id current-state-id] :as fsm} _resources]
                                               (when (= current-state-id ::fsm/start)
                                                 (reset! captured last-state-id))
                                               fsm)}})
          _result (fsm/run fsm-spec {} {:trace [{:state-id :some-previous-state :status :success}]
                                        :data {}})]
      (is (= :some-previous-state @captured))
      (is (keyword? @captured)))))

(deftest compile-eagerly-validates-dispatches
  (testing "invalid dispatch targets are caught at compile time"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"invalid dispatch"
         (fsm/compile {:fsm {::fsm/start {:handler    (fn [_ d] d)
                                          :dispatches [[:nonexistent (constantly true)]]}}})))))

(deftest compile-inline-skips-sci
  (testing "inline dispatch predicates are used directly, not evaluated as SCI forms"
    (is (= {:ready? true}
           (fsm/run
            (fsm/compile
             {:fsm {::fsm/start {:handler    (fn [_ data] (assoc data :ready? true))
                                 :dispatches [[::fsm/end :ready?]]}}})))))
  (testing "inline fn dispatch predicates preserve identity after compile"
    (let [my-pred (fn [data] (:x data))
          compiled (fsm/compile
                    {:fsm {::fsm/start {:handler    (fn [_ d] d)
                                        :dispatches [[::fsm/end my-pred]]}}})
          compiled-pred (-> compiled :fsm (get ::fsm/start) :dispatches first second)]
      (is (identical? my-pred compiled-pred)))))

(deftest run-async-sync-fsm
  (testing "run-async wraps sync FSM result in a deref-able future/promise"
    #?(:clj
       (let [result @(fsm/run-async
                      (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                       :dispatches [[::fsm/end (constantly true)]]}}}))]
         (is (= {:x 1} result)))
       :cljs
       (let [spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                   :dispatches [[::fsm/end (constantly true)]]}}})]
         (async done
                (-> (fsm/run-async spec)
                    (p/then (fn [result]
                              (is (= {:x 1} result))
                              (done)))))))))

(deftest run-async-async-fsm
  (testing "run-async works with async FSM"
    #?(:clj
       (let [result @(fsm/run-async
                      (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data cb _err]
                                                                     (cb (assoc data :x 1)))
                                                       :async?     true
                                                       :dispatches [[::fsm/end (constantly true)]]}}}))]
         (is (= {:x 1} result)))
       :cljs
       (let [spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data cb _err]
                                                                 (cb (assoc data :x 1)))
                                                   :async?     true
                                                   :dispatches [[::fsm/end (constantly true)]]}}})]
         (async done
                (-> (fsm/run-async spec)
                    (p/then (fn [result]
                              (is (= {:x 1} result))
                              (done)))))))))

(deftest duration-ms-in-traces
  (testing "trace segments include :duration-ms"
    (let [result (fsm/run
                  (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                    :dispatches [[:foo (constantly true)]]}
                                       :foo        {:handler    (fn [_resources data] (assoc data :y 2))
                                                    :dispatches [[::fsm/end (constantly true)]]}}
                                :opts {:post (fn [fsm _resources] fsm)}})
                  {}
                  {})
          halted (fsm/run
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] data)
                                                   :dispatches [[::fsm/halt (constantly true)]]}}}))]
      (is (= 1 (count (:trace halted))))
      (is (every? #(and (contains? % :duration-ms)
                        (number? (:duration-ms %)))
                  (:trace halted))))))

(deftest duration-ms-in-error-traces
  (testing "error trace segments include :duration-ms"
    (let [result (fsm/run
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (throw (ex-info "test error" {})))
                                                   :dispatches [[::fsm/end (constantly true)]]}
                                      ::fsm/error {:handler (fn [_resources fsm] fsm)}}}))]
      (is (= :error (:status (first (:trace result)))))
      (is (number? (:duration-ms (first (:trace result))))))))

(deftest async-override-force-async
  (testing "force async execution on a sync-compiled FSM via state map"
    #?(:clj
       (let [result @(fsm/run
                      (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                       :dispatches [[::fsm/end (constantly true)]]}}})
                      {}
                      {:async? true})]
         (is (= {:x 1} result)))
       :cljs
       (let [spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                   :dispatches [[::fsm/end (constantly true)]]}}})]
         (async done
                (-> (fsm/run spec {} {:async? true})
                    (p/then (fn [result]
                              (is (= {:x 1} result))
                              (done)))))))))

(deftest async-override-force-sync
  (testing "force sync execution on an async-compiled FSM via state map"
    (let [result (fsm/run
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                                 (assoc data :x 1))
                                                   :dispatches [[::fsm/end (constantly true)]]}}})
                  {}
                  {:async? false})]
      (is (= {:x 1} result)))))

(deftest async-override-default-unchanged
  (testing "default behavior respects compile-time detection when :async? not in state"
    (let [sync-spec  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                      :dispatches [[::fsm/end (constantly true)]]}}})
          async-spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data cb _err]
                                                                    (cb (assoc data :x 1)))
                                                      :async?     true
                                                      :dispatches [[::fsm/end (constantly true)]]}}})]
      (is (= {:x 1} (fsm/run sync-spec {} {})))
      #?(:clj
         (is (= {:x 1} @(fsm/run async-spec {} {})))
         :cljs
         (async done
                (-> (fsm/run async-spec {} {})
                    (p/then (fn [result]
                              (is (= {:x 1} result))
                              (done)))))))))

(deftest analyze-reachable-states
  (testing "identifies all reachable states from start"
    (let [analysis (fsm/analyze
                    {:fsm {::fsm/start {:handler    identity
                                        :dispatches [[:a (constantly true)]]}
                           :a          {:handler    identity
                                        :dispatches [[:b (constantly true)]]}
                           :b          {:handler    identity
                                        :dispatches [[::fsm/end (constantly true)]]}
                           :orphan     {:handler    identity
                                        :dispatches [[::fsm/end (constantly true)]]}}})]
      (is (contains? (:reachable analysis) ::fsm/start))
      (is (contains? (:reachable analysis) :a))
      (is (contains? (:reachable analysis) :b))
      (is (not (contains? (:reachable analysis) :orphan))))))

(deftest analyze-unreachable-states
  (testing "identifies unreachable states"
    (let [analysis (fsm/analyze
                    {:fsm {::fsm/start {:handler    identity
                                        :dispatches [[:a (constantly true)]]}
                           :a          {:handler    identity
                                        :dispatches [[::fsm/end (constantly true)]]}
                           :orphan1    {:handler    identity
                                        :dispatches [[::fsm/end (constantly true)]]}
                           :orphan2    {:handler    identity
                                        :dispatches [[:orphan1 (constantly true)]]}}})]
      (is (= #{:orphan1 :orphan2} (:unreachable analysis))))))

(deftest analyze-no-path-to-end
  (testing "identifies states with no path to ::end"
    (let [analysis (fsm/analyze
                    {:fsm {::fsm/start {:handler    identity
                                        :dispatches [[:a (constantly true)]]}
                           :a          {:handler    identity
                                        :dispatches [[:b (constantly true)]]}
                           :b          {:handler    identity
                                        :dispatches [[:a (constantly true)]]}}})]
      (is (contains? (:no-path-to-end analysis) :a))
      (is (contains? (:no-path-to-end analysis) :b)))))

(deftest analyze-cycles
  (testing "detects cycles in the FSM"
    (let [analysis (fsm/analyze
                    {:fsm {::fsm/start {:handler    identity
                                        :dispatches [[:a (constantly true)]]}
                           :a          {:handler    identity
                                        :dispatches [[:b (constantly true)]
                                                     [::fsm/end (constantly true)]]}
                           :b          {:handler    identity
                                        :dispatches [[:a (constantly true)]]}}})]
      (is (seq (:cycles analysis)))
      (is (some #(and (contains? (set %) :a) (contains? (set %) :b))
                (:cycles analysis))))))

(deftest analyze-no-duplicate-cycles
  (testing "each cycle is reported only once regardless of starting node"
    (let [analysis (fsm/analyze
                    {:fsm {::fsm/start {:handler    identity
                                        :dispatches [[:a (constantly true)]]}
                           :a          {:handler    identity
                                        :dispatches [[:b (constantly true)]
                                                     [::fsm/end (constantly true)]]}
                           :b          {:handler    identity
                                        :dispatches [[:a (constantly true)]]}}})]
      (is (= 1 (count (:cycles analysis)))))))

(deftest analyze-well-formed-fsm
  (testing "a well-formed FSM has no unreachable states or dead ends"
    (let [analysis (fsm/analyze
                    {:fsm {::fsm/start {:handler    identity
                                        :dispatches [[:a (constantly true)]]}
                           :a          {:handler    identity
                                        :dispatches [[:b (constantly true)]]}
                           :b          {:handler    identity
                                        :dispatches [[::fsm/end (constantly true)]]}}})]
      (is (empty? (:unreachable analysis)))
      (is (empty? (:no-path-to-end analysis)))
      (is (empty? (:cycles analysis))))))
