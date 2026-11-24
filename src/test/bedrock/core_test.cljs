(ns bedrock.core-test
  (:require ["@minecraft/server" :as mc]
            ["@minecraft/server-ui" :as mui]
            [bedrock.commands :as cmd]
            [bedrock.core :as bc]
            [bedrock.ui :as ui]
            [cljs.test :refer [deftest is testing async use-fixtures]]))

(def ^js mock mc/__mock)
(def ^js ui-mock mui/__mock)

(use-fixtures :each {:before #(.reset mock)})

(deftest names
  (is (= "playerSpawn" (bc/camel "player-spawn")))
  (is (= "scriptEventReceive" (bc/camel :script-event-receive)))
  (is (= "demo:hello" (bc/->id :demo/hello)))
  (is (= "x:y" (bc/->id "x:y")))
  (is (= {"nameTag" "a" "checkForBlocks" false}
         (js->clj (bc/->js {:name-tag "a" :check-for-blocks false})))))

(deftest vectors
  (is (= [1 2 3] (bc/v3->vec (bc/v3 1 2 3))))
  (is (= [1 2 3] (bc/v3->vec (bc/v3 [1 2 3]))))
  (is (= [1 2 3] (bc/v3->vec (bc/v3 {:x 1 :y 2 :z 3})))))

(deftest events
  (testing "keyword -> signal"
    (is (= "world.afterEvents.playerSpawn" (.-name (bc/signal :player-spawn))))
    (is (= "world.beforeEvents.chatSend" (.-name (bc/signal :before/chat-send))))
    (is (= "system.afterEvents.scriptEventReceive" (.-name (bc/signal :system/script-event-receive))))
    (is (= "system.beforeEvents.startup" (.-name (bc/signal :system.before/startup))))
    (is (thrown? js/Error (bc/signal :bogus/thing))))
  (testing "subscribe, fire, unsubscribe"
    (let [seen (atom [])
          off (bc/on :player-spawn #(swap! seen conj (.-n ^js %)))]
      (.fire mock "world.afterEvents.playerSpawn" #js {:n 1})
      (off)
      (.fire mock "world.afterEvents.playerSpawn" #js {:n 2})
      (is (= [1] @seen))))
  (testing "filter options are camelCased"
    (bc/on :system/script-event-receive {:namespaces ["demo"]} identity)
    (let [sub (aget (.-subs (bc/signal :system/script-event-receive)) 0)]
      (is (= ["demo"] (js->clj (.. sub -opts -namespaces))))))
  (testing "handler exceptions don't escape"
    (bc/on :player-spawn (fn [_] (throw (ex-info "boom" {:x 1}))))
    (is (nil? (.fire mock "world.afterEvents.playerSpawn" #js {}))))
  (testing "once"
    (let [n (atom 0)]
      (bc/once :item-use #(swap! n inc))
      (.fire mock "world.afterEvents.itemUse" #js {})
      (.fire mock "world.afterEvents.itemUse" #js {})
      (is (= 1 @n)))))

(deftest scheduling
  (let [log (atom [])]
    (bc/run-after 3 #(swap! log conj :after))
    (let [id (bc/run-every 2 #(swap! log conj :every))]
      (.tick mock 4)
      (bc/cancel-run id)
      (.tick mock 4))
    (is (= [:every :after :every] @log))
    (is (= 60 (bc/seconds->ticks 3)))))

(deftest world-atom
  (.set (.-dynamic mock) "demo:state" (pr-str {:stored true}))
  (let [a (bc/world-atom :demo/state {:default true})]
    (testing "default until the world loads; no writes in early execution"
      (is (= {:default true} @a)))
    (.startup mock)
    (testing "loaded from storage"
      (is (= {:stored true} @a)))
    (testing "changes are persisted as EDN"
      (swap! a assoc :n 1)
      (is (= {:stored true :n 1} (bc/edn :demo/state))))))

(deftest commands
  (let [p (mc/Player. "Alex")
        heard (atom nil)]
    (cmd/register! {:name :demo/echo
                    :description "echo"
                    :params [[:text :string]]
                    :optional [[:times :integer]]}
                   (fn [{:keys [player]} args]
                     (reset! heard [(.-name player) args])
                     (str "echo " (:text args))))
    (cmd/register! {:name :demo/check :mode :restricted
                    :permission :admin
                    :params [[:color [:enum :demo/color]]]
                    :enums {:demo/color ["red" "blue"]}}
                   (fn [_ {:keys [color]}]
                     (if (= color "red") "ok" (throw (ex-info "not red" {})))))
    (let [r (.startup mock)
          echo (aget (.-commands r) "demo:echo")
          check (aget (.-commands r) "demo:check")]
      (testing "definitions"
        (is (= {"name" "demo:echo" "description" "echo" "permissionLevel" 0 "cheatsRequired" false
                "mandatoryParameters" [{"name" "text" "type" "String"}]
                "optionalParameters" [{"name" "times" "type" "Integer"}]}
               (js->clj (.-def echo))))
        (is (= 2 (.. check -def -permissionLevel)))
        (is (= [{"name" "demo:color" "type" "Enum"}] (js->clj (.. check -def -mandatoryParameters))))
        (is (= ["red" "blue"] (js->clj (aget (.-enums r) "demo:color")))))
      (testing "deferred handler runs next tick and replies to the player"
        (is (undefined? ((.-cb echo) #js {:sourceEntity p} "hi" js/undefined)))
        (is (nil? @heard))
        (.tick mock)
        (is (= ["Alex" {:text "hi"}] @heard))
        (is (= ["echo hi"] (js->clj (.-messages p)))))
      (testing "restricted handler returns a status"
        (is (= {"status" 0 "message" "ok"} (js->clj ((.-cb check) #js {} "red"))))
        (is (= {"status" 1 "message" "not red"} (js->clj ((.-cb check) #js {} "blue"))))))))

(deftest forms
  (async done
    (let [p (mc/Player. "Steve")]
      (.respond ui-mock #js {:canceled false :selection 1})
      (-> (ui/show-actions! p {:title "T" :buttons [[:a "A"] [:b "B" "textures/b"]]})
          (.then (fn [choice]
                   (is (= :b choice))
                   (.respond ui-mock #js {:canceled true})
                   (ui/show-actions! p {:buttons [[:a "A"]]})))
          (.then (fn [choice]
                   (is (nil? choice))
                   ;; one slot per control, decorations undefined
                   (.respond ui-mock #js {:canceled false :formValues #js ["Bob" js/undefined true 1]})
                   (ui/modal! p {:title "M"
                                 :fields [[:name :text "Name" {}]
                                          ["Options" :header]
                                          [:pvp :toggle "PvP" {}]
                                          [:color :dropdown "Color" {:options ["red" "blue"] :default "blue"}]]})))
          (.then (fn [values]
                   (is (= {:name "Bob" :pvp true :color "blue"} values))
                   (let [controls (js->clj (.. ^js (last (.-shown ui-mock)) -form -controls))]
                     (is (= ["dropdown" "Color" ["red" "blue"] {"defaultValueIndex" 1}] (last controls))))
                   ;; inputs only
                   (.respond ui-mock #js {:canceled false :formValues #js ["Ann" false 0]})
                   (ui/modal! p {:fields [[:name :text "Name" {}]
                                          [nil :divider]
                                          [:pvp :toggle "PvP" {}]
                                          [:color :dropdown "Color" {:options ["red" "blue"]}]]})))
          (.then (fn [values]
                   (is (= {:name "Ann" :pvp false :color "red"} values))
                   (.respond ui-mock #js {:canceled false :selection 0})
                   (ui/confirm! p {:body "sure?"})))
          (.then (fn [yes?] (is (true? yes?))))
          (.catch (fn [e] (is (nil? e) "promise chain failed")))
          (.finally done)))))
