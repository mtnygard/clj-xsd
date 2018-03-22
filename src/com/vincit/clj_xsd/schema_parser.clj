(ns com.vincit.clj-xsd.schema-parser
  "
  For transforming a parsed schema definition into an internal format
  that is easier to work with.
  "
  (:require [com.vincit.clj-xsd.metaschema :as xs]
            [clojure.set :as set]
            [com.vincit.clj-xsd.schema :as hs]
            [com.vincit.clj-xsd.xml :as hx]
            [com.rpl.specter :as sc]))

(defn make-qname [ns name]
  [ns name])

(defn rename-xs [attr]
  (-> attr
      (set/rename-keys {::xs/type ::hs/type
                        ::xs/form ::hs/form})))

(defn group-types [tns types]
  (->> types
       (group-by ::xs/name)
       (sc/transform [sc/MAP-KEYS] (partial make-qname tns))
       (sc/transform [sc/MAP-VALS] (comp #(dissoc % ::xs/name)
                                         first))))

(defn fix-attrs [tns {attrs ::xs/attribute :as type}]
  (->> attrs
       (group-types tns)
       (sc/transform [sc/MAP-VALS] rename-xs)
       (assoc type ::hs/attrs)))

(defn fix-seq-el [tns {max-occurs ::xs/max-occurs
                       min-occurs ::xs/min-occurs
                       name       ::xs/name
                       :as        el}]
  (-> el
      (set/rename-keys {::xs/type ::hs/type})
      (dissoc ::xs/max-occurs ::xs/min-occurs ::xs/name ::xs/element)
      (assoc ::hs/element [tns name])
      (assoc ::hs/multi [min-occurs max-occurs])))

(defn fix-seq [tns seq]
  (let [vals (->> seq
                  (map ::xs/element)
                  first
                  (map (partial fix-seq-el tns)))]
    [::hs/sequence {::hs/vals vals}]))

(defn fix-content [tns {seq    ::xs/sequence
                        choice ::xs/choice
                        :as    type}]
                                        ; TODO choice not supported?
  (-> type
      (assoc ::hs/content (fix-seq tns seq))
      (dissoc ::xs/sequence)))

(defn fix-type [{attrs ::hs/attrs :as type}]
  (cond-> type
    true           (dissoc ::xs/attribute)
    (empty? attrs) (dissoc ::hs/attrs)))

(defn fix-types [tns kind parsed]
  (->> parsed
       kind
       (group-types tns)
       (sc/transform [sc/MAP-VALS] (comp fix-type
                                         (partial fix-attrs tns)
                                         (partial fix-content tns)))))

(defn fix-elems [{:keys [::xs/element] :as schema} tns]
  (-> schema
      (dissoc ::xs/element)
      (assoc ::hs/elems (->> (group-types tns element)
                             (sc/transform [sc/MAP-VALS] rename-xs)))))

(defn schema-to-internal
  "
  Turns a parsed schema document into a more
  consice internal representation"
  [{parsed ::xs/schema}]
  (let [tns               (::xs/target-namespace parsed)
        complex           (fix-types tns ::xs/complex-type parsed)
        simple            (fix-types tns ::xs/simple-type parsed)]
    (-> parsed
        (set/rename-keys {::xs/target-namespace       ::hs/tns
                          ::xs/element-form-default   ::hs/el-default
                          ::xs/attribute-form-default ::hs/attr-default})
        (fix-elems tns)
        (assoc ::hs/types (merge complex
                                 simple))
        (dissoc ::xs/complex-type ::xs/simple-type))))