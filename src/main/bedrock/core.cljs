(ns bedrock.core
  "Idiomatic ClojureScript over @minecraft/server: events, scheduling,
  messages, vectors and EDN-backed dynamic properties."
  (:require ["@minecraft/server" :as mc]
            [cljs.reader :as reader]
            [clojure.string :as str]))

;; Bedrock's console.log goes to the content log / server console.
(enable-console-print!)

(def ^js world mc/world)
(def ^js system mc/system)

;; ---------------------------------------------------------------------------
;; Names

(defn camel
  "\"player-spawn\" -> \"playerSpawn\""
  [s]
  (str/replace (name s) #"-([a-z0-9])" (fn [[_ c]] (str/upper-case c))))

(defn ->id
  "Namespaced keyword to a Bedrock identifier: :demo/hello -> \"demo:hello\".
  Strings pass through."
  [k]
  (cond
    (string? k) k
    (namespace k) (str (namespace k) ":" (name k))
    :else (name k)))

(defn ->js
  "clj->js with kebab-case keys turned into camelCase, so option maps read
  naturally: {:name-tag \"x\" :check-for-blocks false}."
  [x]
  (clj->js x :keyword-fn camel))

;; ---------------------------------------------------------------------------
;; Vectors

(defn v3
  "A Bedrock Vector3.  Accepts x y z, a [x y z] vector or an {:x :y :z} map."
  ([v] (cond
         (vector? v) (let [[x y z] v] (v3 x y z))
         (map? v) (v3 (:x v) (:y v) (:z v))
         :else v))
  ([x y z] #js {:x x :y y :z z}))

(defn v3->vec [^js v] [(.-x v) (.-y v) (.-z v)])

;; ---------------------------------------------------------------------------
;; Events
;;
;; Events are named by keyword:
;;   :player-spawn                 world.afterEvents.playerSpawn
;;   :before/chat-send             world.beforeEvents.chatSend
;;   :system/script-event-receive  system.afterEvents.scriptEventReceive
;;   :system.before/startup        system.beforeEvents.startup

(def ^:private event-roots
  {nil             [:world "afterEvents"]
   "after"         [:world "afterEvents"]
   "before"        [:world "beforeEvents"]
   "system"        [:system "afterEvents"]
   "system.after"  [:system "afterEvents"]
   "system.before" [:system "beforeEvents"]})

(defn signal
  "The event signal object for keyword `k`."
  ^js [k]
  (let [[root group] (or (get event-roots (namespace k))
                         (throw (ex-info (str "Unknown event namespace in " k) {:event k})))
        root (if (= root :world) world system)]
    (or (some-> (unchecked-get root group) (unchecked-get (camel (name k))))
        (throw (ex-info (str "No such event: " k " (" group "." (camel (name k)) ")")
                        {:event k})))))

(defn report-error
  "Log an exception with its ex-data to the content log."
  [where e]
  (js/console.error
   (str "[" where "] " (or (ex-message e) e)
        (when-let [d (ex-data e)] (str " " (pr-str d)))
        (when-let [s (.-stack ^js e)] (str "\n" s)))))

(defn on
  "Subscribe `f` to event `k`; returns a no-arg fn that unsubscribes.
  `opts` is an optional filter map, e.g. {:entity-types [\"minecraft:cow\"]}.
  Exceptions from `f` are logged with their ex-data rather than lost."
  ([k f] (on k nil f))
  ([k opts f]
   (let [sig (signal k)
         handler (fn [ev]
                   (try (f ev)
                        (catch :default e (report-error k e))))
         cb (if opts
              (.subscribe sig handler (->js opts))
              (.subscribe sig handler))]
     (fn [] (.unsubscribe sig cb)))))

(defn once
  "Like `on`, but unsubscribes after the first event."
  [k f]
  (let [off (volatile! nil)]
    (vreset! off (on k (fn [ev] (@off) (f ev))))
    @off))

;; ---------------------------------------------------------------------------
;; Scheduling (Bedrock has no setTimeout; everything runs on game ticks,
;; 20 per second).

(defn run-next
  "Run `f` on the next tick, outside restricted/early execution."
  [f] (.run system f))

(defn run-after [ticks f] (.runTimeout system f ticks))
(defn run-every [ticks f] (.runInterval system f ticks))
(defn cancel-run [id] (.clearRun system id))

(defn wait-ticks
  "Promise resolving after `n` ticks."
  [n] (.waitTicks system n))

(defn seconds->ticks [s] (js/Math.round (* 20 s)))

;; ---------------------------------------------------------------------------
;; Messages

(defn- msg [xs] (apply str xs))

(defn say
  "Broadcast to every player."
  [& xs] (.sendMessage world (msg xs)))

(defn tell
  "Message one player."
  [^js player & xs] (.sendMessage player (msg xs)))

(defn players
  "Seq of online players, optionally filtered by an EntityQueryOptions map."
  ([] (array-seq (.getAllPlayers world)))
  ([opts] (array-seq (.getPlayers world (->js opts)))))

(defn player? [^js e]
  (and (some? e) (= "minecraft:player" (.-typeId e))))

(defn dimension
  "(dimension :overworld) / :nether / :the-end"
  ^js [k]
  (.getDimension world (str "minecraft:" (str/replace (name k) "-" "_"))))

(defn run-command
  "Run a slash command (without the /) as `target` (an entity or dimension;
  defaults to the overworld)."
  ([cmd] (run-command (dimension :overworld) cmd))
  ([^js target cmd] (.runCommand target cmd)))

;; ---------------------------------------------------------------------------
;; Dynamic properties
;;
;; Bedrock stores booleans, numbers, strings and Vector3s.  `prop` /
;; `set-prop!` use those directly; `edn` / `set-edn!` store any EDN value
;; as a string.  Owners are the world, an entity or an ItemStack.

(defn prop
  ([k] (prop world k))
  ([^js owner k] (.getDynamicProperty owner (->id k))))

(defn set-prop!
  ([k v] (set-prop! world k v))
  ([^js owner k v] (.setDynamicProperty owner (->id k) v)))

(defn edn
  ([k] (edn world k))
  ([owner k] (some-> (prop owner k) reader/read-string)))

(defn set-edn!
  ([k v] (set-edn! world k v))
  ([owner k v] (set-prop! owner k (when (some? v) (pr-str v)))))

(defn world-atom
  "An atom whose value persists in a world dynamic property under `k` (EDN).

  World storage can't be touched during early execution, so the atom starts
  at `default`, loads the stored value when the world loads, and from then on
  writes every change back.  Use with defonce."
  [k default]
  (let [a (atom default)
        load! (fn []
                (let [stored (edn k)]
                  (when (some? stored) (reset! a stored)))
                (add-watch a ::persist (fn [_ _ old new]
                                         (when (not= old new) (set-edn! k new)))))]
    (once :world-load (fn [_] (load!)))
    a))
