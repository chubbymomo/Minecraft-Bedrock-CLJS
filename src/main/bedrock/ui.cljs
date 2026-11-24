(ns bedrock.ui
  "Data-driven forms over @minecraft/server-ui.  Every show-* fn returns a
  Promise of a plain Clojure value (nil when the player closes the form)."
  (:require ["@minecraft/server-ui" :as ui]))

(defn show-actions!
  "Menu of buttons.  `buttons` is [[key label] or [key label icon-path] ...].
  Resolves to the chosen key, or nil.

    (ui/show-actions! player {:title \"Warp\" :body \"Where to?\"
                              :buttons [[:spawn \"Spawn\"] [:home \"Home\"]]})"
  [player {:keys [title body buttons]}]
  (let [^js form (ui/ActionFormData.)]
    (when title (.title form title))
    (when body (.body form body))
    (doseq [[_ label icon] buttons]
      (if icon (.button form label icon) (.button form label)))
    (-> (.show form player)
        (.then (fn [^js r]
                 (when-not (.-canceled r)
                   (first (nth buttons (.-selection r) nil))))))))

(defn confirm!
  "Two-button dialog.  Resolves to true, false, or nil if closed."
  [player {:keys [title body yes no] :or {yes "Yes" no "No"}}]
  (let [^js form (ui/MessageFormData.)]
    (when title (.title form title))
    (.body form (or body ""))
    (.button1 form yes)
    (.button2 form no)
    (-> (.show form player)
        (.then (fn [^js r]
                 (when-not (.-canceled r)
                   (zero? (.-selection r))))))))

(defn- add-field! [^js form [k kind label opts]]
  (case kind
    :text     (.textField form label (or (:placeholder opts) "")
                          #js {:defaultValue (or (:default opts) "")})
    :toggle   (.toggle form label #js {:defaultValue (boolean (:default opts))})
    :slider   (.slider form label (:min opts 0) (:max opts 10)
                       #js {:defaultValue (:default opts (:min opts 0))
                            :valueStep (:step opts 1)})
    :dropdown (.dropdown form label (into-array (:options opts))
                         #js {:defaultValueIndex (or (some->> (:default opts)
                                                              (.indexOf (:options opts)))
                                                     0)})
    :label    (.label form k)
    :header   (.header form k)
    :divider  (.divider form)))

(defn- input? [field] (not (#{:label :header :divider} (second field))))

(defn modal!
  "Form with inputs.  Each field is [key kind label opts]:

    [:name  :text     \"Name\"   {:placeholder \"Steve\" :default \"\"}]
    [:pvp   :toggle   \"PvP\"    {:default true}]
    [:size  :slider   \"Size\"   {:min 1 :max 64 :step 1 :default 8}]
    [:color :dropdown \"Color\"  {:options [\"red\" \"blue\"] :default \"blue\"}]

  plus decorations [\"text\" :label], [\"text\" :header] and [nil :divider].
  Dropdowns resolve to the chosen option.  Resolves to {key value}, or nil."
  [player {:keys [title fields submit]}]
  (let [^js form (ui/ModalFormData.)]
    (.title form (or title ""))
    (doseq [f fields] (add-field! form f))
    (when submit (.submitButton form submit))
    (-> (.show form player)
        (.then (fn [^js r]
                 (when-not (.-canceled r)
                   (let [values (array-seq (.-formValues r))
                         inputs (filter input? fields)
                         ;; formValues has a slot per control; decorations
                         ;; hold undefined.  Tolerate either layout.
                         pairs (if (= (count values) (count fields))
                                 (filter (comp input? first) (map vector fields values))
                                 (map vector inputs values))]
                     (into {}
                           (for [[[k kind _ opts] v] pairs]
                             [k (if (= kind :dropdown) (nth (:options opts) v nil) v)])))))))))
