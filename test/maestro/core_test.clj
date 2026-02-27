(ns maestro.core-test
  (:refer-clojure :exclude [compile])
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [maestro.core :as fsm]))

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
          (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (/ 1 0))
                                           :dispatches [[::fsm/end (constantly true)]]}}})))
    (is (= 1 0))
    (catch Exception ex
      (is (= "execution error" (-> ex ex-data :error (.getMessage)))))))

(deftest custom-error-handler
  (->> (fsm/run
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (/ 1 0))
                                         :dispatches [[::fsm/end (constantly true)]]}
                            ::fsm/error {:handler (fn [_resources {:keys [error]}]
                                                    (-> error ex-data :error (.getMessage)))}}}))
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

(deftest async
  (->> (fsm/run
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                       (assoc data :foo :bar))
                                         :dispatches [[:foo     (fn [_state] true)]]}
                            :foo       {:handler    (fn [_resources data cb _error]
                                                      (cb (assoc data :x 1)))
                                        :async?     true
                                        :dispatches [[:bar (constantly true)]]}
                            :bar       {:handler    (fn [_resources data] (assoc data :y 2))
                                        :dispatches [[::fsm/end (constantly true)]]}}}))
       (= {:foo :bar
           :x   1
           :y   2})
       (is)))

(deftest async-error
  (try
    (->> (fsm/run
          (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                         (assoc data :foo :bar))
                                           :dispatches [[:foo (fn [_state] true)]]}
                              :foo       {:handler    (fn [_resources _data _cb error]
                                                        (error (ex-info "error" {})))
                                          :async?     true
                                          :dispatches [[:bar (constantly true)]]}
                              :bar       {:handler    (fn [_resources data] (assoc data :y 2))
                                          :dispatches [[::fsm/end (constantly true)]]}}})))
    (catch Exception ex
      (is (= (.getMessage ex) "execution error")))))

(deftest edn-spec
  (let [spec (fsm/compile (edn/read-string (slurp "test/fsm.edn"))
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
    (is (= 2 (count (:trace state))))
    (is (= ::fsm/start (:state-id (first (:trace state)))))
    (is (= :success (:status (first (:trace state)))))
    (is (number? (:duration-ms (first (:trace state)))))
    (is (= :foo (:state-id (second (:trace state)))))
    (is (= :success (:status (second (:trace state)))))
    (is (= {:max-trace 1000, :subscriptions {}} (:opts state)))
    (is (= {:foo    :bar
            :y      3
            :ready? true}
           (fsm/run fsm {} (assoc-in state [:data :ready?] true))))))

(deftest subscriptions-test
  (let [x (atom nil)]
    (fsm/run
     (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data]
                                                     (assoc data :foo :bar))
                                       :dispatches [[:foo (constantly true)]]}
                          :foo       {:handler    (fn [_resources data]
                                                    (update-in data [:x :y] inc))
                                      :dispatches [[:bar (constantly true)]]}
                          :bar       {:handler    (fn [_resources data cb _err] (cb (update-in data [:x :y] inc)))
                                      :async?     true
                                      :dispatches [[::fsm/end (constantly true)]]}}
                   :opts {:subscriptions {[:x :y] {:handler (fn [path old-value new-value] 
                                                              (reset! x {path [old-value new-value]}))}}}})
     {}
     {:data {:x {:y 1}}})
    (is (= @x {[:x :y] [2 3]}))))

(deftest pre-post-test
  (->> (fsm/run
        (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data]
                                                        (assoc data :foo :bar))
                                          :dispatches [[:foo (constantly true)]]}
                             :foo       {:handler    (fn [_resources data] (assoc data :y 2))
                                         :dispatches [[:bar (constantly true)]]}
                             :bar       {:handler    (fn [_resources data cb _err] (cb (assoc data :z 3)))
                                         :async?     true
                                         :dispatches [[::fsm/end (constantly true)]]}}
                      :opts {:pre  (fn [fsm _resources] (assoc-in fsm [:data :pre-value] 1))
                             :post (fn [fsm _resources] (assoc-in fsm [:data :post-value] 2))}}))
       (= {:pre-value  1
           :foo        :bar
           :post-value 2
           :y          2
           :z          3})
       (is))
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
  ;; Tests fix for error-handler -> error-callback
  ;; When no dispatch matches, error callback should be properly invoked
  (testing "error when no dispatch matches"
    (try
      (fsm/run
       (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                        ;; No dispatch will match since we return false
                                        :dispatches [[::fsm/end (constantly false)]]}}}))
      (is false "Should have thrown an error")
      (catch Exception ex
        ;; The error is wrapped by default-on-error
        (is (= "execution error" (.getMessage ex)))
        ;; The original error is in the :error key of ex-data
        (let [original-error (-> ex ex-data :error)]
          (is (= "invalid target state transition" (.getMessage original-error)))
          (is (= ::fsm/start (-> original-error ex-data :current-state-id)))
          (is (nil? (-> original-error ex-data :target-state-id))))))))

(deftest error-trace-consistency
  ;; Tests fix for consistent trace keys (:state-id vs :current-state-id)
  ;; Error traces should use :state-id like success traces
  (testing "error traces use :state-id key"
    (let [result (fsm/run
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources _data] (throw (ex-info "test error" {})))
                                                   :dispatches [[::fsm/end (constantly true)]]}
                                      ::fsm/error {:handler (fn [_resources fsm] fsm)}}}))]
      ;; Check that the error trace segment uses :state-id
      (is (every? #(contains? % :state-id) (:trace result)))
      (is (not-any? #(contains? % :current-state-id) (:trace result))))))

(deftest last-state-id-type
  ;; Tests fix for last-state-id being a keyword, not a map
  ;; When resuming from halt, last-state-id should be the state keyword
  (testing "last-state-id is a keyword after resuming"
    (let [fsm-spec (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :step1 1))
                                                    :dispatches [[:middle (constantly true)]]}
                                       :middle     {:handler    (fn [_resources data] (assoc data :step2 2))
                                                    :dispatches [[::fsm/halt (constantly true)]]}
                                       :final      {:handler    (fn [_resources data] (assoc data :step3 3))
                                                    :dispatches [[::fsm/end (constantly true)]]}}})
          ;; Run until halt
          halted-state (fsm/run fsm-spec)]
      ;; Verify last-state-id is nil (no previous state before start)
      (is (nil? (:last-state-id halted-state)))
      ;; Now resume with a trace that has entries
      (let [result (fsm/run fsm-spec {} (assoc halted-state
                                               :current-state-id :final
                                               :trace [{:state-id ::fsm/start :status :success}
                                                       {:state-id :middle :status :success}]))]
        ;; The resumed run should complete successfully with step3 added
        (is (= 3 (:step3 result)))))))

(deftest last-state-id-from-trace
  ;; More direct test: verify last-state-id is extracted correctly from trace
  ;; The fix ensures (:state-id (last trace)) is used, not (last trace) which would be a map
  (testing "last-state-id extracted from trace on resume"
    (let [captured (atom nil)
          fsm-spec (fsm/compile {:fsm  {::fsm/start {:handler    (fn [_resources data] data)
                                                     :dispatches [[::fsm/end (constantly true)]]}}
                                 :opts {:pre (fn [{:keys [last-state-id current-state-id] :as fsm} _resources]
                                               ;; Capture last-state-id on first state (before it gets overwritten)
                                               (when (= current-state-id ::fsm/start)
                                                 (reset! captured last-state-id))
                                               fsm)}})
          _result (fsm/run fsm-spec {} {:trace [{:state-id :some-previous-state :status :success}]
                                        :data {}})]
      ;; last-state-id should be the keyword from the trace, not the whole map
      (is (= :some-previous-state @captured))
      (is (keyword? @captured)))))

;; Feature: Timing in traces

(deftest trace-timing
  (testing "trace segments include :duration-ms for sync handlers"
    (let [result (fsm/run
                  (fsm/compile
                   {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                     (Thread/sleep 10)
                                                     (assoc data :x 1))
                                       :dispatches [[:next (constantly true)]]}
                          :next       {:handler    (fn [_resources data] (assoc data :y 2))
                                       :dispatches [[::fsm/end (constantly true)]]}
                          ::fsm/end   {:handler (fn [_resources fsm] fsm)}}}))]
      (is (= 2 (count (:trace result))))
      (is (every? #(contains? % :duration-ms) (:trace result)))
      (is (every? #(number? (:duration-ms %)) (:trace result)))
      ;; The sleep handler should take at least 10ms
      (is (>= (:duration-ms (first (:trace result))) 10)))))

(deftest trace-timing-async
  (testing "trace segments include :duration-ms for async handlers"
    (let [result (fsm/run
                  (fsm/compile
                   {:fsm {::fsm/start {:handler    (fn [_resources data cb _err]
                                                     (Thread/sleep 10)
                                                     (cb (assoc data :x 1)))
                                       :async?     true
                                       :dispatches [[:next (constantly true)]]}
                          :next       {:handler    (fn [_resources data] (assoc data :y 2))
                                       :dispatches [[::fsm/end (constantly true)]]}
                          ::fsm/end   {:handler (fn [_resources fsm] fsm)}}}))]
      (is (= 2 (count (:trace result))))
      (is (every? #(contains? % :duration-ms) (:trace result)))
      (is (>= (:duration-ms (first (:trace result))) 10)))))

(deftest trace-timing-on-error
  (testing "error trace segments include :duration-ms"
    (let [result (fsm/run
                  (fsm/compile
                   {:fsm {::fsm/start {:handler    (fn [_resources _data]
                                                     (Thread/sleep 10)
                                                     (throw (ex-info "boom" {})))
                                       :dispatches [[::fsm/end (constantly true)]]}
                          ::fsm/error {:handler (fn [_resources fsm] fsm)}}}))]
      (is (= 1 (count (:trace result))))
      (is (contains? (first (:trace result)) :duration-ms))
      (is (>= (:duration-ms (first (:trace result))) 10)))))

;; Feature: run-async

(deftest run-async-basic
  (testing "run-async returns a future with the result"
    (let [result (fsm/run-async
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (assoc data :x 1))
                                                   :dispatches [[::fsm/end (constantly true)]]}}}))]
      (is (future? result))
      (is (= {:x 1} @result)))))

(deftest run-async-with-resources
  (testing "run-async passes resources through"
    (let [result (fsm/run-async
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [{:keys [multiplier]} data]
                                                                 (assoc data :x (* multiplier 5)))
                                                   :dispatches [[::fsm/end (constantly true)]]}}})
                  {:multiplier 3})]
      (is (= {:x 15} @result)))))

(deftest run-async-with-state
  (testing "run-async passes initial state through"
    (let [result (fsm/run-async
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data] (update data :x inc))
                                                   :dispatches [[::fsm/end (constantly true)]]}}})
                  {}
                  {:data {:x 10}})]
      (is (= {:x 11} @result)))))

(deftest run-async-non-blocking
  (testing "run-async does not block the calling thread"
    (let [started (atom false)
          latch (java.util.concurrent.CountDownLatch. 1)
          result (fsm/run-async
                  (fsm/compile {:fsm {::fsm/start {:handler    (fn [_resources data]
                                                                 (.await latch)
                                                                 (assoc data :done true))
                                                   :dispatches [[::fsm/end (constantly true)]]}}}))]
      (reset! started true)
      ;; We got here without blocking, proving run-async is non-blocking
      (is @started)
      (is (not (realized? result)))
      (.countDown latch)
      (is (= {:done true} (deref result 5000 :timeout))))))

;; Feature: Static analysis

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
      ;; a and b form a cycle with no exit to ::end
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
      ;; Should find a cycle involving :a and :b
      (is (some #(and (contains? (set %) :a) (contains? (set %) :b))
                (:cycles analysis))))))

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
