(ns websocket-fx.pows
  (:require [re-frame.core :as rf]
            [clojure.string :as strings]
            [haslett.format :as formats]
            [haslett.client :as haslett]
            [cljs.core.async :as async]))

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

(rf/reg-event-fx ::connect
                 (fn [{:keys [db]} [_ socket-id command]]
                   (let [data {:status :pending :options command}]
                     {:db       (assoc-in db [::sockets socket-id] data)
                      ::connect {:socket-id socket-id :options command}})))

(rf/reg-event-fx ::disconnect
                 (fn [{:keys [db]} [_ socket-id]]
                   {:db          (dissoc-in db [::sockets socket-id])
                    ::disconnect {:socket-id socket-id}}))

(rf/reg-event-fx ::connected
                 (fn [{:keys [db]} [_ socket-id]]
                   {:db
                    (assoc-in db [::sockets socket-id :status] :connected)
                    :dispatch-n
                    (vec (for [sub (vals (get-in db [::sockets socket-id :subscriptions] {}))]
                           [::subscribe socket-id (get sub :id) sub]))}))

(rf/reg-event-fx ::disconnected
                 (fn [{:keys [db]} [_ socket-id cause]]
                   (let [options (get-in db [::sockets socket-id :options])]
                     {:db
                      (assoc-in db [::sockets socket-id :status] :reconnecting)
                      :dispatch-n
                      (vec (for [request-id (keys (get-in db [::sockets socket-id :requests] {}))]
                             [::request-timeout socket-id request-id cause]))
                      :dispatch-later
                      [{:ms 2000 :dispatch [::connect socket-id options]}]})))

;;; REQUESTS

(rf/reg-event-fx ::request
                 (fn [{:keys [db]} [_ socket-id {:keys [message timeout] :as command}]]
                   (let [payload (cond-> {:id (random-uuid) :proto :request :data message}
                                   (some? timeout) (assoc :timeout timeout))
                         path    [::sockets socket-id :requests (get payload :id)]]
                     {:db         (assoc-in db path command)
                      ::ws-message {:socket-id socket-id :message payload}})))

(rf/reg-event-fx ::request-response
                 (fn [{:keys [db]} [_ socket-id request-id & more]]
                   (let [path    [::sockets socket-id :requests request-id]
                         request (get-in db path)]
                     (cond-> {:db (dissoc-in db path)}
                       (contains? request :on-response)
                       (assoc :dispatch (concatv (:on-response request) more))))))

(rf/reg-event-fx ::request-timeout
                 (fn [{:keys [db]} [_ socket-id request-id & more]]
                   (let [path    [::sockets socket-id :requests request-id]
                         request (get-in db path)]
                     (cond-> {:db (dissoc-in db path)}
                       (contains? request :on-timeout)
                       (assoc :dispatch (concatv (:on-timeout request) more))))))

;;; SUBSCRIPTIONS

(rf/reg-event-fx ::subscribe
                 (fn [{:keys [db]} [_ socket-id topic {:keys [message] :as command}]]
                   (let [path    [::sockets socket-id :subscriptions topic]
                         payload {:id topic :proto :subscription :data message}]
                     {:db          (assoc-in db path command)
                      ::ws-message {:socket-id socket-id :message payload}})))

(rf/reg-event-fx ::subscription-message
                 (fn [{:keys [db]} [_ socket-id subscription-id & more]]
                   (let [path         [::sockets socket-id :subscriptions subscription-id]
                         subscription (get-in db path)]
                     (cond-> {}
                       (contains? subscription :on-message)
                       (assoc :dispatch (concatv (:on-message subscription) more))))))

(rf/reg-event-fx ::unsubscribe
                 (fn [{:keys [db]} [_ socket-id subscription-id & more]]
                   (let [path         [::sockets socket-id :subscriptions subscription-id]
                         payload      {:id subscription-id :proto :subscription :close true}
                         subscription (get-in db path)]
                     (cond-> {:db (dissoc-in db path)}
                       (some? subscription)
                       (assoc ::ws-message {:socket-id socket-id :message payload})
                       (contains? subscription :on-close)
                       (assoc :dispatch (concatv (:on-close subscription) more))))))

(rf/reg-event-fx ::subscription-closed
                 (fn [{:keys [db]} [_ socket-id subscription-id & more]]
                   (let [path [::sockets socket-id :subscriptions subscription-id]]
                     (if-some [subscription (get-in db path)]
                       (cond-> {:db (dissoc-in db path)}
                         (contains? subscription :on-close)
                         (assoc :dispatch (concatv (:on-close subscription) more)))))))

;;; PUSH

(rf/reg-event-fx ::push
                 (fn [_ [_ socket-id command]]
                   (let [payload {:id (random-uuid) :proto :push :data command}]
                     {::ws-message {:socket-id socket-id :message payload}})))

;;; FX HANDLERS

(rf/reg-fx
 ::connect
 (fn [{socket-id
       :socket-id
       {:keys [url format on-connect on-disconnect on-message]
        :or   {format :edn
               url    (websocket-url)}}
       :options}]
   (let [sink-proxy (async/chan 100)]
     (swap! CONNECTIONS assoc socket-id {:sink sink-proxy})
     (async/go
       (let [{:keys [socket in out close-status] :as connect-result}
             (async/<! (haslett/connect url {:format (keyword->format format)}))
             mult (async/mult in)]
         (println "haslett socket created (connect result: " connect-result ")")
         (swap! CONNECTIONS assoc socket-id {:sink sink-proxy :source in  :socket socket})
         (async/go
           (println "ws: checking for closed websocket")
           (when-some [closed (async/<! close-status)]
             (rf/dispatch [::disconnected socket-id closed])
             (when (some? on-disconnect) (rf/dispatch on-disconnect))))
         (when-not (async/poll! close-status)
           (async/go-loop []
             (println "ws: waiting for incoming messages")
             (when-some [incoming (async/<! sink-proxy)]
               (println "ws: received message: " incoming)
               (println "dispatching incoming-ws-message to channel " on-message ", message: " incoming)
               (rf/dispatch [on-message incoming]))
             (recur))
           (rf/dispatch [::connected socket-id])
           (when (some? on-connect) (rf/dispatch on-connect))))))))

(rf/reg-fx
 ::disconnect
 (fn [{:keys [socket-id]}]
   (let [{:keys [socket]} (get (first (swap-vals! CONNECTIONS dissoc socket-id)) socket-id)]
     (when (some? socket) (.close socket)))))

(rf/reg-fx
 ::ws-message
 (fn [{:keys [socket-id message]}]
   (println "ws: preparing to send message")
   (if-some [{:keys [sink]} (get @CONNECTIONS socket-id)]
     (println  "ws: sending message")
     (async/put! sink message)
     (.error js/console "Socket with id " socket-id " does not exist."))))

;;; INTROSPECTION

(rf/reg-sub
 ::pending-requests
 (fn [db [_ socket-id]]
   (vals (get-in db [::sockets socket-id :requests]))))

(rf/reg-sub
 ::open-subscriptions
 (fn [db [_ socket-id]]
   (vals (get-in db [::sockets socket-id :subscriptions]))))

(rf/reg-sub
 ::status
 (fn [db [_ socket-id]]
   (get-in db [::sockets socket-id :status])))
