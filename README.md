## Maestro

![build status](https://github.com/yogthos/maestro/actions/workflows/main.yml/badge.svg)

Maestro is a state machine runner for expressing workflows.

While the idea of writing applications in a pure functional style is appealing, it's not always clear how to separate side effects from pure compuation in practice. Variations of Clean Architecture approach are often suggested as a way to accomplish this goal. This style dictates that IO should be handled in the outer layer that wraps pure computation core of the application.

While this notion is appealing, it only works in cases where the totality of the data that will be operated on is known up front. Unfortunately, it's impossible to know ahead of time what data will be needed in most real world applications. In many cases additional data needs to load conditionally based on the type of input and the current state of processing.

What we can do, however, is break up our application into small components that can be reasoned about in isolation. Such components can then be composed together to accomplish tasks of increased complexity. I like to think of this as a Lego model of software development. Each component can be viewed as a Lego block, and we can compose these Lego block in many different ways as we solve different problems.

The problem being solved can be expressed in terms of a workflow represented by a graph where the nodes compute the state, and the edges represent transitions between the states. Each time we enter a node in this graph, we look at the input, decide what additional data we may need, run the computation, and transition to the next state. Each node in the graph is a Lego block that accomplishes a particular task. These nodes are then connected by a layer of code governs the data flow.

Maestro implements the above architecture using a map to describe overall state, then pass it through agraph of functions that produce a new state. Each function takes the state map as a parameter, does some operations on it, and then returns a new map that gets passed to the next function.

### Installation

Add the following dependency to your project:

```clojure
{:deps {io.github.yogthos/maestro {:git/tag "v0.1.1" 
                                   :git/sha "737cc97"}}}
```

### Usage

The state machine is defined using a map where the identified for each state points to the handler for that state:

```clojure
{:fsm {:foo {:handler (fn [state] (assoc state :foo :bar))
             :dispatches [[:maestro.core/end (constantly true)]]}}
 :opts {}}
```

The state handler consists of a map containing the following keys:

* `:handler` - the handler function accepts input state and return the updated state
* `:dispatches` - a vector of dispatch targets along with functions that accept the current state and return a truthy value, the key associated with the first truthy return value will be executed
* `:async?` - optional key to indicate that the handler is an async function
  
### Privileged states

Maestro uses four special states that represent the start, halt, end, and error states:

* `:maestro.core/start` - initial state the FSM is placed in
* `:maestro.core/end` - the end state when the execution stops
* `:maestro.core/halt` - returns the state of the FSM that can be restarted
* `:maestro.core/error` - error state when the execution stops

The spec is compiled using `maestro.core/compile` and then executed using `maestro.core/run`.
The spec contains the following keys:

* `:fsm` - the FSM spec that will be executed
* `:current-state-id` - the state from which the FSM will execute
* `:last-state-id` - the last state that FSM was in
* `:data` - initial data the FSM will operate on
* `:trace` - the log of states that the FSM transitioned through (defaults to 1000)
* `:opts` - metadata
  * `:max-trace` - indicates custom trace size
  * `:subscriptions` - subscriptions to state changes for executing side effects
  * `:pre` - function called before the handler executes, accepts the current FSM state and returns it
  * `:post` - function called after the handler executes, accepts the current FSM state and returns it

### Complete example

```clojure
(require '[maestro.core :as fsm])

;; FSM that counts to 4 and return
(-> (fsm/compile
     {:fsm {::fsm/start {:handler    (fn [data]
                                       (update data :count (fnil inc 0)))
                         :dispatches [[::fsm/end (fn [state] (> (:count state) 3))]
                                      [::fsm/start (constantly true)]]}}})
    (fsm/run))
=> {:count 4}

;; FSM that has an initial state and trace size
(-> (fsm/compile
     {:fsm {::fsm/start {:handler    (fn [data]
                                       (update data :count (fnil inc 0)))
                         :dispatches [[::fsm/end (fn [state] (> (:count state) 3))]
                                      [::fsm/start (constantly true)]]}}
      :opts {:max-trace 10}})
    (fsm/run {:foo :bar}))
=> {:foo :bar, :count 4}

;; subscription handler
(fsm/run
 (fsm/compile {:fsm  {::fsm/start {:handler    (fn [data]
                                                 (assoc data :x {:y 1}))
                                   :dispatches [[:foo (constantly true)]]}
                      :foo        {:handler    (fn [data] (update-in data [:x :y] inc))
                                   :dispatches [[::fsm/end (constantly true)]]}}
               :opts {:subscriptions {[:x :y] {:handler (fn [path old-value new-value] 
                                                          (println "path" path
                                                                   "old value" old-value 
                                                                   "new value" new-value ))}}}}))
=> path [:x :y] old value nil new value 1 ;; subscription handler output
=> path [:x :y] old value 1 new value 2 ;; subscription handler output
=> {:x {:y 2}}

;; FSM that uses an async handler
(-> (fsm/compile
     {:fsm {::fsm/start {:handler    (fn [data]
                                       (update data :count (fnil inc 0)))
                         :dispatches [[:foo (fn [state] (> (:count state) 3))]
                                      [::fsm/start (constantly true)]]}
            :foo      {:handler (fn [data callback _error-callback]
                                  (callback (assoc data :foo :bar)))
                       :async? true
                       :dispatches [[::fsm/end (constantly true)]]}}})
    (fsm/run))
=> {:count 4, :foo :bar}

;; FSM with pre and post interceptors
(fsm/run
 (fsm/compile {:fsm  {::fsm/start {:handler    (fn [data] (update data :x inc))
                                   :dispatches [[:foo (constantly true)]]}
                      :foo       {:handler    (fn [data] (update data :x inc))
                                  :dispatches [[::fsm/end (constantly true)]]}}
               :opts {:pre  (fn [{:keys [current-state-id]
                                  :as   fsm}]
                              (println "pre" current-state-id)
                              (update-in fsm [:data :pre] (fnil conj [])
                                         {:pre  current-state-id
                                          :time (System/currentTimeMillis)}))
                      :post (fn [{:keys [current-state-id]
                                  :as   fsm}]
                              (update-in fsm [:data :post] (fnil conj [])
                                         {:post current-state-id
                                          :time (System/currentTimeMillis)}))}})
 {:x 1})
=> {:x 3
    :pre [{:pre :maestro.core/start :time 1681995016315}
          {:pre :foo, :time 1681995016316}
          {:pre :maestro.core/end :time 1681995016316}]
    :post [{:post :maestro.core/start :time 1681995016315}
           {:post :foo, :time 1681995016316}
           {:post :maestro.core/end :time 1681995016316}]}
```

### EDN Spec

FSM spec can be written to an EDN file, dispatches will be compiled using [SCI](https://github.com/babashka/sci).
However, handlers must be supplied using an additional map.

Given the following `fsm.edn`

```clojure
{:fsm {:maestro.core/start {:handler :foo
                            :dispatches [[:maestro.core/end (fn [{:keys [v]}] (= v 5))]]}}}
```

FSM can be instantiated as follows:

```clojure
(-> (compile (edn/read-string (slurp "test/fsm.edn"))
             {:foo (fn [data] (assoc data :v 5))})
    (run))
=> {:v 5}  
```
