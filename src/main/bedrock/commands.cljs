(ns bedrock.commands
  "Custom slash commands with data-driven definitions.

    (cmd/register!
      {:name :demo/heal
       :description \"Heal a player\"
       :permission :game-directors
       :params [[:target :player-selector]]
       :optional [[:amount :integer]]}
      (fn [{:keys [player]} {:keys [target amount]}] ...))

  The handler gets a context map {:origin :source :player} and a map of
  argument name -> value (missing optionals are absent)."
  (:require ["@minecraft/server" :as mc]
            [bedrock.core :as bc]
            [goog.object :as gobj]))

(def ^:private param-types
  {:block-type "BlockType" :boolean "Boolean" :entity-selector "EntitySelector"
   :entity-type "EntityType" :enum "Enum" :float "Float" :integer "Integer"
   :item-type "ItemType" :location "Location" :player-selector "PlayerSelector"
   :string "String"})

(def ^:private permissions
  {:any "Any" :game-directors "GameDirectors" :admin "Admin" :host "Host" :owner "Owner"})

(defn- lookup [enum table k]
  (let [n (or (get table k) (throw (ex-info (str "Unknown value " k) {:value k :allowed (keys table)})))]
    (gobj/get enum n)))

(defn- param
  "[name type] or [name [:enum \"ns:enum\"]].  For enums the Bedrock
  parameter name *is* the enum name, so we use that and remember the
  local key separately."
  [[k t]]
  (if (vector? t)
    #js {:name (bc/->id (second t)) :type (lookup mc/CustomCommandParamType param-types :enum)}
    #js {:name (name k) :type (lookup mc/CustomCommandParamType param-types t)}))

(defn- result [status message]
  (cond-> #js {:status status}
    message (doto (gobj/set "message" (str message)))))

(defn- run-handler [mode handler ctx args]
  (case mode
    ;; Runs inside the command callback: may return a string (shown as the
    ;; command's success message) but may not modify the world.
    :restricted
    (try
      (result mc/CustomCommandStatus.Success (handler ctx args))
      (catch :default e
        (bc/report-error "command" e)
        (result mc/CustomCommandStatus.Failure (or (ex-message e) (str e)))))

    ;; Default: deferred to the next tick so the handler can change the
    ;; world.  Return values are sent to the invoking player, if any.
    :deferred
    (do (bc/run-next
         (fn []
           (try
             (when-let [m (handler ctx args)]
               (some-> (:player ctx) (bc/tell m)))
             (catch :default e
               (bc/report-error "command" e)
               (some-> (:player ctx) (bc/tell "§c" (or (ex-message e) (str e))))))))
        js/undefined)))

(defn register!
  "Register a custom command at startup.  Call from your init fn.

  Keys: :name (namespaced keyword or \"ns:name\"), :description,
  :permission (:any :game-directors :admin :host :owner; default :any),
  :cheats? (default false), :params / :optional (vectors of [key type]),
  :enums {\"ns:enum\" [\"a\" \"b\"]}, :mode (:deferred default, or :restricted)."
  [{:keys [name description permission cheats? params optional enums mode]
    :or {permission :any cheats? false mode :deferred}}
   handler]
  (let [all-keys (mapv first (concat params optional))]
    (bc/on :system.before/startup
           (fn [^js ev]
             (let [^js registry (.-customCommandRegistry ev)]
               (doseq [[enum-name values] enums]
                 (.registerEnum registry (bc/->id enum-name) (clj->js values)))
               (.registerCommand
                registry
                #js {:name (bc/->id name)
                     :description (or description "")
                     :permissionLevel (lookup mc/CommandPermissionLevel permissions permission)
                     :cheatsRequired cheats?
                     :mandatoryParameters (into-array (map param params))
                     :optionalParameters (into-array (map param optional))}
                (fn [^js origin]
                  (let [values (.call (.-slice js/Array.prototype) (js-arguments) 1)
                        args (into {} (keep-indexed
                                       (fn [i k]
                                         (let [v (aget values i)]
                                           (when-not (undefined? v) [k v]))))
                                   all-keys)
                        source (.-sourceEntity origin)
                        ctx {:origin origin
                             :source source
                             :player (when (bc/player? source) source)}]
                    (run-handler mode handler ctx args)))))))))
