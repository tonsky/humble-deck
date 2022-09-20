(ns humble-deck.overview
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [humble-deck.controls :as controls]
    [humble-deck.core :refer :all]
    [humble-deck.scaler :as scaler]
    [io.github.humbleui.canvas :as canvas]
    [io.github.humbleui.core :as core]
    [io.github.humbleui.debug :as debug]
    [io.github.humbleui.font :as font]
    [io.github.humbleui.paint :as paint]
    [io.github.humbleui.protocols :as protocols]
    [io.github.humbleui.typeface :as typeface]
    [io.github.humbleui.ui :as ui]
    [io.github.humbleui.window :as window])
  (:import
    [io.github.humbleui.types IPoint IRect]
    [io.github.humbleui.skija Color ColorSpace]
    [io.github.humbleui.jwm Window]
    [io.github.humbleui.jwm.skija LayerMetalSkija]
    [java.lang AutoCloseable]))

(set! *warn-on-reflection* true)

(def overview-padding
  10)

(def zoom-time
  1500)

(defn slide-size [{:keys [width height]}]
  (let [per-row (max 1 (quot width 200))
        slide-w (-> width
                  (- (* (inc per-row) overview-padding))
                  (quot per-row))]
    {:per-row per-row
     :slide-w slide-w
     :slide-h (/ slide-w ratio)}))

(core/deftype+ Zoomer [*state per-row slide-w slide-h child ^:mut child-rect]
  protocols/IComponent
  (-measure [_ ctx cs]
    (core/measure child ctx cs))
  
  (-draw [_ ctx rect ^Canvas canvas]
    (set! child-rect rect)
    (let [{:keys [current start end]} @*state]
      (if (or start end)
        (let [progress (cond
                         start
                         (min 1 (/ (- (core/now) start) zoom-time))
                       
                         end
                         (max 0 (- 1 (/ (- (core/now) end) zoom-time))))]
          (when (and start (>= progress 1))
            (swap! *state assoc :mode :present :start nil))
          (when (and end (<= progress 0))
            (swap! *state assoc :end nil))
          (let [{:keys [scale window]} ctx
                row            (quot current per-row)
                column         (mod current per-row)
                slide-x        (* scale (+ overview-padding (* column (+ slide-w overview-padding)) (/ slide-w 2)))
                slide-y        (* scale (+ overview-padding (* row (+ slide-h overview-padding)) (/ slide-h 2)))
              
                half-slide-h   (* scale (/ slide-h 2))
                scroll         (-> child :child :offset)
                _              (cond
                                 (< (+ slide-y scroll) (+ (:y rect) half-slide-h))
                                 (protocols/-set! (:child child) :offset (- (+ (:y rect) half-slide-h) slide-y))
                               
                                 (> (+ slide-y scroll) (- (:bottom rect) (+ half-slide-h)))
                                 (protocols/-set! (:child child) :offset (- (- (:bottom rect) half-slide-h) slide-y)))
              
                scroll         (-> child :child :offset)
                slide-y        (+ slide-y (-> child :child :offset))
              
                screen-x       (+ (:x rect) (/ (:width rect) 2))
                screen-y       (+ (:y rect) (/ (:height rect) 2))
                target-zoom    (min
                                 (-> (:width rect) (/ ratio) (/ slide-h scale))
                                 (-> (:height rect) (* ratio) (/ slide-w scale)))
                zoom           (+ 1 (* progress (- target-zoom 1)))
                target-slide-x (+ slide-x (* progress (- screen-x slide-x)))
                target-slide-y (+ slide-y (* progress (- screen-y slide-y)))
              
                transformed-slide-x (* zoom slide-x)
                transformed-slide-y (* zoom slide-y)]
            (with-open [paint (paint/fill (Color/makeLerp(unchecked-int 0xFFFFFFFF) (unchecked-int 0xFFF0F0F0) progress))]
              (canvas/draw-rect canvas rect paint))
            (canvas/with-canvas canvas
              (canvas/translate canvas
                (- target-slide-x transformed-slide-x)
                (- target-slide-y transformed-slide-y))
              (canvas/scale canvas zoom)
              (core/draw-child child ctx rect canvas))
            (window/request-frame window)))
        (do
          (canvas/draw-rect canvas rect (paint/fill 0xFFF0F0F0))
          (core/draw-child child ctx rect canvas)))))
  
  (-event [_ ctx event]
    (core/event-child child ctx event))
  
  (-iterate [this ctx cb]
    (or
      (cb this)
      (protocols/-iterate child ctx cb)))
  
  AutoCloseable
  (close [_]
    (core/child-close child)))

(defn zoomer [*state per-row slide-w slide-h child]
  (->Zoomer *state per-row slide-w slide-h child nil))

(defn overview [slides]
  (ui/with-bounds ::bounds
    (ui/dynamic ctx [{:keys [scale]} ctx
                     {:keys [per-row slide-w slide-h]} (slide-size (::bounds ctx))]
      (zoomer *state per-row slide-w slide-h
        (ui/vscrollbar
          (ui/vscroll
            (ui/padding 10
              (let [cap-height (quot (min slide-h (/ slide-w ratio)) 10)
                    font-body  (font/make-with-cap-height typeface-regular cap-height)
                    font-h1    (font/make-with-cap-height typeface-bold cap-height)
                    full-len   (-> (count slides) (dec) (quot per-row) (inc) (* per-row))
                    slides'    (concat slides (repeat (- full-len (count slides)) nil))]
                (ui/with-context
                  {:font-body font-body
                   :font-h1   font-h1
                   :font-ui   font-body
                   :leading   (quot cap-height 2)
                   :unit      (quot cap-height 10)}
                  (ui/column
                    (interpose (ui/gap 0 overview-padding)
                      (for [row (partition per-row (core/zip (range) slides'))]
                        (ui/height slide-h
                          (ui/row
                            (interpose (ui/gap overview-padding 0)
                              (for [[idx slide] row
                                    :let [slide-comp (ui/rect (paint/fill 0xFFFFFFFF)
                                                       (if (delay? slide) @slide slide))]]
                                [:stretch 1
                                 (when slide
                                   (ui/clickable
                                     {:on-click
                                      (fn [_]
                                        (swap! *state assoc
                                          :current idx
                                          :start   (core/now)))}
                                     (ui/clip-rrect 4
                                       (ui/dynamic ctx [{:hui/keys [hovered?]} ctx
                                                        {:keys [start end mode]} @*state]
                                         (if (and (= :overview mode) (nil? start) (nil? end) hovered?)
                                           (ui/stack
                                             slide-comp
                                             (ui/rect (paint/fill 0x20000000)
                                               (ui/gap 0 0)))
                                           slide-comp)))))]))))))))))))))))