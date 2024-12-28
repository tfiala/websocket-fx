(ns websocket-fx.pows
  (:require [re-frame.core :as rf]
            [clojure.string :as strings]
            [haslett.format :as formats]
            [haslett.client :as haslett]
            [cljs.core.async :refer [<! >! go go-loop poll! put!]]))

(defonce CONNECTIONS (atom {}))

(defn get-websocket-port []
  (str (aget js/window "location" "port")))

(defn get-websocket-host []
  (str (aget js/window "location" "hostname")))

(defn get-websocket-proto []
  (let [proto (str (aget js/window "location" "protocol"))]
    (get {"http:" "ws" "https:" "wss"} proto)))

(defn websocket-url []
  (let [proto (get-websocket-proto)
        host  (get-websocket-host)
        port  (get-websocket-port)
        path  "/ws"]
    (if (strings/blank? port)
      (str proto "://" host path)
      (str proto "://" host ":" port path))))

(defn dissoc-in [m [k & ks]]
  (if ks
    (if (map? (get m k))
      (update m k #(dissoc-in % ks))
      m)
    (dissoc m k)))

(defn concatv [& more]
  (vec (apply concat more)))

(def keyword->format
  {:edn                  formats/edn
   :json                 formats/json
   :text                 formats/identity
   :transit-json         formats/transit
   :transit-json-verbose formats/transit})

;;; SOCKETS

(rf/reg-event-fx
 ::connect
 (fn [{:keys [db]} [_ socket-id command]]
   (let [data {:status :pending :options command}]
     {:db       (assoc-in db [::sockets socket-id] data)
      ::connect {:socket-id socket-id :options command}})))

(rf/reg-event-fx
 ::disconnect
 (fn [{:keys [db]} [_ socket-id]]
   {:db          (dissoc-in db [::sockets socket-id])
    ::disconnect {:socket-id socket-id}}))

(rf/reg-event-fx
 ::connected
 (fn [{:keys [db]} [_ socket-id]]
   {:db
    (assoc-in db [::sockets socket-id :status] :connected)}))

(rf/reg-event-fx
 ::disconnected
 (fn [{:keys [db]} [_ socket-id _cause]]
   (let [options (get-in db [::sockets socket-id :options])]
     {:db
      (assoc-in db [::sockets socket-id :status] :reconnecting)
      :dispatch-later
      [{:ms 2000 :dispatch [::connect socket-id options]}]})))

;;; REQUESTS

(rf/reg-event-fx
 ::send-message
 (fn [_ [_ socket-id {:keys [message _timeout]}]]
   {::ws-message {:socket-id socket-id :message message}}))

;;; FX HANDLERS

(rf/reg-fx
 ::connect
 (fn [{socket-id
       :socket-id
       {:keys [url format on-connect on-disconnect on-message]
        :or   {format :edn
               url    (websocket-url)}}
       :options}]
   (go
     ;; create the websocket
     (let [{:keys [socket in out close-status] :as connect-result}
           (<! (haslett/connect url {:format (keyword->format format)}))]
       ;; (println "haslett socket created (connect result: " connect-result ")")
       (swap! CONNECTIONS assoc socket-id connect-result)

       ;; wait on close-status channel for disconnection notification
       (go
         ;; (println "ws: disconnected handler waiting on websocket to close")
         (when-some [closed (<! close-status)]
           (rf/dispatch [::disconnected socket-id closed])
           (when (some? on-disconnect) (rf/dispatch on-disconnect))))

       ;; read loop
       (when-not (poll! close-status)
         (go-loop []
           ;; (println "ws: waiting for incoming messages")
           (when-some [incoming (<! in)]
             ;; (println "ws: received message: " incoming)
             ;; (println "dispatching incoming-ws-message to channel " on-message ", message: " incoming)
             (rf/dispatch [on-message socket-id incoming]))
           (recur))
         (rf/dispatch [::connected socket-id])
         (when (some? on-connect) (rf/dispatch on-connect)))))))

(rf/reg-fx
 ::disconnect
 (fn [{:keys [socket-id]}]
   (let [{:keys [socket]} (get (first (swap-vals! CONNECTIONS dissoc socket-id)) socket-id)]
     (when (some? socket) (.close socket)))))

(rf/reg-fx
 ::ws-message
 (fn [{:keys [socket-id message]}]
   ;; (println "ws: preparing to send message to socket-id " socket-id)
   (if-some [{:keys [out]} (get @CONNECTIONS socket-id)]
     (do
       ;; (println  "ws: sending message: " message)
       (put! out message))
     (.error js/console "Socket with id " socket-id " does not exist."))))

;;; INTROSPECTION

(rf/reg-sub
 ::status
 (fn [db [_ socket-id]]
   (get-in db [::sockets socket-id :status])))
