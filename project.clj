(defproject tfiala/websocket-fx "0.2.0-tfiala"

  :description
  "A re-frame counterpart for websocket-layer"

  :url
  "https://github.com/tfiala/websocket-fx"

  :license
  {:name "MIT" :url "http://opensource.org/licenses/MIT"}

  :deploy-repositories
  [["releases" :clojars]
   ["snapshots" :clojars]]

  :dependencies
  [[org.clojure/clojure "1.12.0"]
   [org.clojure/clojurescript "1.11.132"]
   [haslett "0.2.0"]
   [org.clojure/core.async "1.7.701"]
   [com.cognitect/transit-cljs "0.8.280"]
   [re-frame "1.4.4"]]

  :source-paths
  ["src"]

  :repl-options
  {:init-ns websocket-fx.core}

  :cljsbuild
  {:builds
   [{:id           "test"
     :source-paths ["test" "src"]
     :compiler     {:preloads             [devtools.preload]
                    :external-config      {:devtools/config {:features-to-install [:formatters :hints]}}
                    :output-to            "run/compiled/browser/test.js"
                    :source-map           true
                    :output-dir           "run/compiled/browser/test"
                    :optimizations        :none
                    :source-map-timestamp true
                    :pretty-print         true}}
    {:id           "karma"
     :source-paths ["test" "src"]
     :compiler     {:output-to     "run/compiled/karma/test.js"
                    :source-map    "run/compiled/karma/test.js.map"
                    :output-dir    "run/compiled/karma/test"
                    :optimizations :whitespace
                    :main          "re_frame_async_flow_fx.test_runner"
                    :pretty-print  true}}]})
