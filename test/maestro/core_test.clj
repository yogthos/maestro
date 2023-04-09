(ns maestro.core-test
  (:refer-clojure :exclude [compile])
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [maestro.core :as fsm]))

(deftest basic-fsm
  (->> (fsm/compile {:fsm {::fsm/start {:handler    (fn [data]
                                                      (assoc data :foo :bar))
                                        :dispatches [[:foo (constantly true)]]} 
                           :foo {:handler (fn [data] (assoc data :y 2))
                                 :dispatches      [[::fsm/end (constantly true)]]}}})
      (fsm/run)
      (= {:foo :bar :y 2})
      (is)))

(deftest basic-fsm-default-values
  (->> (fsm/run 
        (fsm/compile {:fsm {::fsm/start {:handler    (fn [data]
                                                        (assoc data :foo :bar))
                                          :dispatches [[:foo (constantly true)]]}
                             :foo       {:handler    (fn [data] (assoc data :y 2))
                                         :dispatches [[::fsm/end (constantly true)]]}}})
        {:x 1})
       (= {:x   1
           :foo :bar
           :y   2})
       (is)))

(deftest error
  (try
    (->> (fsm/compile {:fsm {::fsm/start {:handler    (fn [_data]
                                                         (/ 1 0))
                                           :dispatches [[::fsm/end (constantly true)]]}}})
         (fsm/run))
    (catch Exception ex
      (is (= "execution error" (-> ex ex-data :error (.getMessage)))))))

(deftest custom-error-handler
  (->> (fsm/compile {:fsm {::fsm/start {:handler    (fn [_data]
                                                       (/ 1 0))
                                         :dispatches [[::fsm/end (constantly true)]]}
                            ::fsm/error {:handler (fn [{:keys [error]}] 
                                                    (-> error ex-data :error (.getMessage)))}}})
       (fsm/run)
       (= "Divide by zero")
       (is)))

(deftest custom-end
  (-> (fsm/compile {:fsm {::fsm/start {:handler    (fn [data]
                                                      (assoc data :foo :bar))
                                        :dispatches [[:foo     (fn [state]
                                                                 (println state)
                                                                 true)]]}
                           :foo {:handler (fn [data] (assoc data :y 2))
                                 :dispatches      [[::fsm/end (constantly true)]]}
                           ::fsm/end {:handler (fn [state] (-> state :data :foo))}}})
      (fsm/run)
      (= :bar)
      (is)))

(deftest cycle-state
  (->> (fsm/compile {:fsm {::fsm/start {:handler (fn [data]
                                                   (update data :count (fnil inc 0)))
                                        :dispatches      [[::fsm/end (fn [state] (> (:count state) 3))]
                                                          [::fsm/start (constantly true)]]}}})
      (fsm/run)
      (= {:count 4})
      (is)))

(deftest async
  (->> (fsm/compile {:fsm {::fsm/start {:handler (fn [data]
                                                   (assoc data :foo :bar))
                                        :dispatches      [[:foo     (fn [state] true)]]}
                           :foo       {:handler    (fn [data cb _error]
                                                     (cb (assoc data :x 1)))
                                       :async?     true
                                       :dispatches [[:bar (constantly true)]]}
                           :bar {:handler (fn [data] (assoc data :y 2))
                                 :dispatches      [[::fsm/end (constantly true)]]}}})
      (fsm/run)
      (= {:foo :bar, :x 1, :y 2})
      (is)))

(deftest async-error
  (try
    (-> (fsm/compile {:fsm {::fsm/start {:handler    (fn [data]
                                                        (assoc data :foo :bar))
                                          :dispatches [[:foo     (fn [state] true)]]}
                             :foo       {:handler    (fn [_data _cb error]
                                                       (error (ex-info "error" {})))
                                         :async?     true
                                         :dispatches [[:bar (constantly true)]]}
                             :bar       {:handler    (fn [data] (assoc data :y 2))
                                         :dispatches [[::fsm/end (constantly true)]]}}})
        (fsm/run))
    (catch Exception ex
      (is (= (.getMessage ex) "execution error")))))

(deftest edn-spec
  (let [spec (fsm/compile (edn/read-string (slurp "test/fsm.edn"))
                          {:foo (fn [data] (assoc data :v 5))})] 
    (is (= {:v 5} (fsm/run spec)))))

(deftest halt-test
  (-> (fsm/compile {:fsm {::fsm/start {:handler    (fn [data]
                                                     (assoc data :foo :bar))
                                       :dispatches [[:foo (constantly true)]]}
                          :foo       {:handler    (fn [data] (assoc data :y 2))
                                      :dispatches [[:bar (fn [data]
                                                           (println data)
                                                           (:ready? data))]
                                                   [::fsm/halt (constantly true)]]}
                          :bar       {:handler    (fn [data] (assoc data :y 2))
                                      :dispatches [[::fsm/end (constantly true)]]}}})
      (fsm/run)
      (assoc-in [:data :ready?] true)
      (fsm/run)
      (= {:foo    :bar
          :y      2
          :ready? true})
      (is)))

(deftest subscriptions-test
  (let [x (atom nil)]
    (fsm/run
     (fsm/compile {:fsm  {::fsm/start {:handler    (fn [data]
                                                     (assoc data :foo :bar))
                                       :dispatches [[:foo (constantly true)]]}
                          :foo       {:handler    (fn [data]
                                                    (update-in data [:x :y] inc))
                                      :dispatches [[:bar (constantly true)]]}
                          :bar       {:handler    (fn [data cb _err] (cb (update-in data [:x :y] inc)))
                                      :async?     true
                                      :dispatches [[::fsm/end (constantly true)]]}}
                   :opts {:subscriptions {[:x :y] {:handler (fn [path old-value new-value] 
                                                              (reset! x {path [old-value new-value]}))}}}})
     {:x {:y 1}})
    (is (= @x {[:x :y] [2 3]}))))


