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
    (is (= {:current-state-id :foo,
            :last-state-id nil,
            :data {:foo :bar, :y 2},
            :trace
            [{:state-id :maestro.core/start, :status :success}
             {:state-id :foo, :status :success}],
            :opts {:max-trace 1000, :subscriptions {}}}
           state))
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
