(ns example.main
  "Demo add-on.  In game, try:
     /demo:menu                open a form
     /demo:heal @s 10          heal (operators)
     /scriptevent demo:ping hi
     use a compass             opens the menu"
  (:require [bedrock.core :as bc]
            [bedrock.commands :as cmd]
            [bedrock.ui :as ui]))

;; Persisted in the world save as EDN: {player-name visit-count}
(defonce visits (bc/world-atom :demo/visits {}))

(defn- greet! [^js player]
  (let [n (get (swap! visits update (.-name player) (fnil inc 0)) (.-name player))]
    (bc/tell player "§aWelcome" (when (> n 1) " back") ", " (.-name player)
             "! §7(visit #" n ")")))

(defn- teleport-home! [^js player]
  (let [^js spawn (.getSpawnPoint player)]
    (if spawn
      (.teleport player (bc/v3 spawn) #js {:dimension (.-dimension spawn)})
      (bc/tell player "§eYou don't have a spawn point yet."))))

(defn- open-menu! [^js player]
  (-> (ui/show-actions! player {:title "CLJS Demo"
                                :body "Pick something"
                                :buttons [[:home "Go to my spawn point"]
                                          [:stats "Who has visited?"]
                                          [:rename "Set a nickname"]]})
      (.then (fn [choice]
               (case choice
                 :home (teleport-home! player)
                 :stats (bc/tell player (pr-str @visits))
                 :rename (-> (ui/modal! player {:title "Nickname"
                                                :fields [[:nick :text "Nickname" {:placeholder (.-name player)}]
                                                         [:shout :toggle "Tell everyone" {:default true}]]})
                             (.then (fn [{:keys [nick shout]}]
                                      (when (seq nick)
                                        (set! (.-nameTag player) nick)
                                        (if shout
                                          (bc/say (.-name player) " is now known as " nick)
                                          (bc/tell player "Nickname set."))))))
                 nil)))))

(defn init []
  (bc/on :player-spawn
         (fn [^js ev]
           (when (.-initialSpawn ev)
             (greet! (.-player ev)))))

  (bc/on :item-use
         (fn [^js ev]
           (when (= "minecraft:compass" (.. ev -itemStack -typeId))
             (open-menu! (.-source ev)))))

  (bc/on :system/script-event-receive {:namespaces ["demo"]}
         (fn [^js ev]
           (bc/say "§dpong: " (.-message ev))))

  (cmd/register! {:name :demo/menu
                  :description "Open the CLJS demo menu"}
                 (fn [{:keys [player]} _]
                   (if player
                     (open-menu! player)
                     "Run this as a player.")))

  (cmd/register! {:name :demo/heal
                  :description "Heal players"
                  :permission :game-directors
                  :params [[:targets :player-selector]]
                  :optional [[:amount :integer]]}
                 (fn [_ {:keys [targets amount] :or {amount 20}}]
                   (doseq [^js p targets
                           :let [^js health (.getComponent p "minecraft:health")]]
                     (.setCurrentValue health (min (.-effectiveMax health)
                                                   (+ (.-currentValue health) amount)))
                     (bc/tell p "§aHealed " amount))
                   nil))

  (println "[cljs-demo] loaded"))
