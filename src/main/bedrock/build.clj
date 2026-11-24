(ns bedrock.build
  "shadow-cljs build hook that turns a compiled :esm build into Bedrock packs.

  On every flush it:
    1. copies static pack files from packs/BP (and packs/RP, if present)
       next to the compiled scripts in target/BP (target/RP),
    2. writes manifest.json for each pack from pack.edn, adding a dependency
       for every @minecraft/* module the compiled code imports,
    3. mirrors the packs into the configured development pack folders,
    4. in release mode, zips both into target/<slug>.mcaddon."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util UUID]
           [java.util.zip ZipEntry ZipOutputStream]))

;; ---------------------------------------------------------------------------
;; Minimal JSON writer (keeps the hook dependency-free)

(defn- json-str [s]
  (str \" (-> s
              (str/replace "\\" "\\\\")
              (str/replace "\"" "\\\"")
              (str/replace "\n" "\\n"))
       \"))

(defn- ->json
  ([x] (->json x ""))
  ([x indent]
   (let [in (str indent "  ")]
     (cond
       (nil? x) "null"
       (or (true? x) (false? x)) (str x)
       (number? x) (str x)
       (keyword? x) (json-str (name x))
       (string? x) (json-str x)
       (map? x) (if (empty? x)
                  "{}"
                  (str "{\n"
                       (str/join ",\n" (for [[k v] x :when (some? v)]
                                         (str in (json-str (name k)) ": " (->json v in))))
                       "\n" indent "}"))
       (and (sequential? x) (every? number? x)) (str "[" (str/join ", " x) "]")
       (sequential? x) (if (empty? x)
                         "[]"
                         (str "[\n" (str/join ",\n" (map #(str in (->json % in)) x))
                              "\n" indent "]"))
       :else (throw (ex-info (str "Can't encode " (pr-str x) " as JSON") {:value x}))))))

;; ---------------------------------------------------------------------------
;; Files

(defn- delete-tree! [^File f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn- copy-tree!
  "Copy every file under `from` into `to`, skipping paths for which
  (skip? relative-path) is true."
  [^File from ^File to skip?]
  (when (.isDirectory from)
    (let [base (.toPath from)]
      (doseq [^File f (file-seq from)
              :when (.isFile f)
              :let [rel (str (.relativize base (.toPath f)))]
              :when (not (skip? rel))]
        (let [dest (io/file to rel)]
          (io/make-parents dest)
          (io/copy f dest))))))

(defn- mirror!
  "Replace `dest` with an exact copy of `src`."
  [^File src ^File dest]
  (delete-tree! dest)
  (copy-tree! src dest (constantly false)))

(defn- zip-dirs!
  "Write a zip at `out` containing each [prefix dir] pair."
  [^File out pairs]
  (io/make-parents out)
  (with-open [zos (ZipOutputStream. (io/output-stream out))]
    (doseq [[prefix ^File dir] pairs
            :let [base (.toPath dir)]
            ^File f (file-seq dir)
            :when (.isFile f)]
      (.putNextEntry zos (ZipEntry. (str prefix "/" (str/replace (str (.relativize base (.toPath f))) File/separator "/"))))
      (io/copy f zos)
      (.closeEntry zos))))

;; ---------------------------------------------------------------------------
;; Manifest generation

(defn- npm-version
  "Version of an installed @minecraft/* package, normalised to what a
  manifest accepts (\"2.12.0-beta.1.26.66-stable\" -> \"2.12.0-beta\")."
  [module]
  (let [pkg (io/file "node_modules" module "package.json")]
    (when (.exists pkg)
      (when-let [[_ v] (re-find #"\"version\"\s*:\s*\"([^\"]+)\"" (slurp pkg))]
        (if-let [[_ base] (re-find #"^(\d+\.\d+\.\d+)-beta" v)]
          (str base "-beta")
          (re-find #"^\d+\.\d+\.\d+" v))))))

(defn- imported-modules
  "Every @minecraft/* module imported by the compiled JS in `scripts-dir`."
  [^File scripts-dir]
  (->> (file-seq scripts-dir)
       (filter #(str/ends-with? (.getName ^File %) ".js"))
       (mapcat #(re-seq #"(?:from|import)\s*[\"'](@minecraft/[a-z0-9-]+)[\"']" (slurp %)))
       (map second)
       (into (sorted-set))))

(defn- script-dependencies [pack scripts-dir]
  (let [overrides (:script-modules pack)
        modules (into (imported-modules scripts-dir) (keys overrides))]
    (vec (for [m modules
               :let [v (or (get overrides m) (npm-version m))]]
           (if v
             {:module_name m :version v}
             (throw (ex-info (str "Don't know which version of " m " to depend on. "
                                  "npm install it or add it to :script-modules in pack.edn.")
                             {:module m})))))))

(defn- require-uuids! [pack paths]
  (let [missing (remove #(get-in pack %) paths)]
    (when (seq missing)
      (throw (ex-info (str "pack.edn is missing UUIDs. Add, for example:\n"
                           (str/join "\n" (for [p missing]
                                            (str "  " (pr-str p) " \"" (UUID/randomUUID) "\""))))
                      {:missing missing})))))

(defn- header
  ([pack uuid suffix] (header pack uuid suffix nil))
  ([pack uuid suffix pack-scope]
   (array-map
    :name (str (:name pack) suffix)
    :description (:description pack "")
    :uuid uuid
    ;; Cooperative add-on guidelines: RPs declare "pack_scope": "world",
    ;; right after the uuid.
    :pack_scope pack-scope
    :version (:version pack [1 0 0])
    :min_engine_version (:min-engine-version pack [1 21 90]))))

(defn- bp-manifest [pack scripts-dir entry rp?]
  (require-uuids! pack [[:bp :header-uuid] [:bp :data-uuid] [:bp :script-uuid]])
  (let [version (:version pack [1 0 0])]
    (cond-> {:format_version 2
             :header (header pack (get-in pack [:bp :header-uuid]) "")
             :modules [{:type "data" :uuid (get-in pack [:bp :data-uuid]) :version version}
                       {:type "script" :language "javascript"
                        :uuid (get-in pack [:bp :script-uuid])
                        :entry entry :version version}]
             :dependencies (cond-> (script-dependencies pack scripts-dir)
                             rp? (conj {:uuid (get-in pack [:rp :header-uuid]) :version version}))}
      (seq (:capabilities pack)) (assoc :capabilities (vec (:capabilities pack))))))

(defn- rp-manifest [pack]
  (require-uuids! pack [[:rp :header-uuid] [:rp :module-uuid]])
  (let [version (:version pack [1 0 0])]
    {:format_version 2
     :header (header pack (get-in pack [:rp :header-uuid]) " Resources" "world")
     :modules [{:type "resources" :uuid (get-in pack [:rp :module-uuid]) :version version}]
     :dependencies [{:uuid (get-in pack [:bp :header-uuid]) :version version}]}))

;; ---------------------------------------------------------------------------
;; The hook

(defn- deploy-dir [pack k env]
  (some-> (or (System/getenv env) (get-in pack [:deploy k])) not-empty io/file))

(defn- log [& xs] (println (apply str "[bedrock] " xs)))

(defn pack
  "shadow-cljs :build-hooks entry: (bedrock.build/pack \"pack.edn\")"
  {:shadow.build/stage :flush}
  [build-state pack-file]
  (let [pack (edn/read-string (slurp pack-file))
        slug (:slug pack "addon")
        release? (= :release (:shadow.build/mode build-state))
        scripts-dir (io/file (get-in build-state [:shadow.build/config :output-dir]))
        bp-dir (.getParentFile (.getAbsoluteFile scripts-dir))
        out-dir (.getParentFile bp-dir)
        rp-src (io/file "packs" "RP")
        rp? (.isDirectory rp-src)
        rp-dir (io/file out-dir "RP")
        entry (str (.relativize (.toPath bp-dir) (.toPath (.getAbsoluteFile (io/file scripts-dir "main.js")))))]

    ;; A release main.js is self-contained; drop leftovers from dev builds
    ;; so they don't ship.
    (when release?
      (delete-tree! (io/file scripts-dir "cljs-runtime")))

    ;; BP: static files from packs/BP, but never let them clobber scripts/
    (copy-tree! (io/file "packs" "BP") bp-dir
                #(or (= % "manifest.json") (str/starts-with? % "scripts")))
    (spit (io/file bp-dir "manifest.json") (->json (bp-manifest pack scripts-dir entry rp?)))

    (when rp?
      (delete-tree! rp-dir)
      (copy-tree! rp-src rp-dir #(= % "manifest.json"))
      (spit (io/file rp-dir "manifest.json") (->json (rp-manifest pack))))

    (when-let [d (deploy-dir pack :behavior-packs "BEDROCK_BP_DIR")]
      (let [dest (io/file d (str slug "_BP"))]
        (mirror! bp-dir dest)
        (log "deployed behavior pack -> " dest)))
    (when-let [d (and rp? (deploy-dir pack :resource-packs "BEDROCK_RP_DIR"))]
      (let [dest (io/file d (str slug "_RP"))]
        (mirror! rp-dir dest)
        (log "deployed resource pack -> " dest)))

    (when release?
      (let [out (io/file out-dir (str slug ".mcaddon"))]
        (zip-dirs! out (cond-> [[(str slug "_BP") bp-dir]]
                         rp? (conj [(str slug "_RP") rp-dir])))
        (log "wrote " out)))

    build-state))
