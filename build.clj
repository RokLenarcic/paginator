(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deploy]
            [clojure.tools.deps :as t]))

(def version (format "1.0.%s" (b/git-count-revs nil)))

(defn create-opts [cli-opts aliases]
  (let [lib 'org.clojars.roklenarcic/paginator
        target (:target cli-opts "target")
        scm (cond (:tag cli-opts) {:tag (:tag cli-opts)}
                  version {:tag (str "v" version)})]
    (merge {:basis (if aliases (b/create-basis {:aliases aliases}) (b/create-basis))
            :lib lib :version version :class-dir (str target "/classes") :target target
            :src-dirs ["src"] :resource-dirs ["resources"] :scm scm
            :jar-file (format "%s/%s-%s.jar" target (name lib) version)}
           cli-opts)))

(defn clean [opts] (b/delete {:path "target"}) opts)

(defn run-tests [opts]
  (let [opts (create-opts opts [:test])
        cmd (b/java-command (assoc opts
                              :jvm-opts (:jvm-opts (t/combine-aliases (:basis opts) [:test]))
                              :main 'clojure.main
                              :main-args ["-m" "cognitect.test-runner"]))
        {:keys [exit]} (b/process cmd)]
    (when-not (zero? exit)
      (throw (ex-info "Tests failed" {})))
    opts))

(defn jar [opts]
  (let [{:keys [src-dirs resource-dirs] :as opts} (create-opts opts nil)]
    (b/write-pom opts)
    (b/copy-dir {:src-dirs (concat src-dirs resource-dirs)
                 :target-dir (:class-dir opts)})
    (b/jar opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (create-opts opts nil)]
    (println (format "Installing %s locally." (:jar-file opts)))
    (b/install opts))
  opts)

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (run-tests)
      (clean)
      (jar)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [opts (create-opts opts nil)]
    (deploy/deploy {:installer :remote
                    :sign-releases? true
                    :artifact (b/resolve-path (:jar-file opts))
                    :pom-file (b/pom-path opts)}))
  opts)
