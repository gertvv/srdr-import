(ns app.core
  (:require [org.drugis.addis.rdf.trig :as trig]))

(use 'korma.db 'korma.core)

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defdb db (mysql {:db (System/getenv "SRDR_IMPORT_DB_NAME")
                  :host (System/getenv "SRDR_IMPORT_DB_HOST")
                  :user (System/getenv "SRDR_IMPORT_DB_USER")
                  :password (System/getenv "SRDR_IMPORT_DB_PASS")}))

(declare outcomes)

(defentity primary_publication_numbers)

(defentity primary_publications
  (has-many primary_publication_numbers {:fk :primary_publication_id}))

(defentity secondary_publications)
; ignoring secondary_publication_numbers - there is no way to enter them

(defentity arms)

(defentity outcome_timepoints
  (belongs-to outcomes {:fk :outcome_id}))

(defentity outcome_subgroups
  (belongs-to outcomes {:fk :outcome_id}))

(defentity outcome_data_entries
  (belongs-to outcomes {:fk :outcome_id}))

(defentity outcome_measures
  (belongs-to outcome_data_entries {:fk :outcome_data_entry_id}))

(defentity outcome_data_points
  (belongs-to arms {:fk :arm_id})
  (belongs-to outcome_measures {:fk :outcome_measure_id}))

(defentity outcomes
  (has-many outcome_timepoints {:fk :outcome_id})
  (has-many outcome_subgroups {:fk :outcome_id})
  (has-many outcome_data_entries {:fk :outcome_id}))

(defentity studies
  (has-one primary_publications {:fk :study_id})
  (has-many secondary_publications {:fk :study_id})
  (has-many arms {:fk :study_id})
  (has-many outcomes {:fk :study_id}))

(defentity projects
  (has-many studies {:fk :project_id}))

(defn pmid-rdf
  [pmid]
  (trig/_po [(trig/iri :ontology "has_id") (trig/iri (str "http://pubmed.com/" pmid))]
            [(trig/iri :dc "identifier") (trig/iri (str "info:pmid/" pmid))]))


(defn publication-rdf
  [table id]
  (let [pub (first (select table (where {:id id})))
        journal (trig/_po [(trig/iri :rdf "type") (trig/iri :bibo "Journal")]
                          [(trig/iri :dc "title") (trig/lit (:journal pub))])]
    (if (:pmid pub)
      (pmid-rdf (:pmid pub))
      (trig/_po [(trig/iri :dc "title") (trig/lit (:title pub))]
                [(trig/iri :dc "creator") (trig/lit (:author pub))]
                [(trig/iri :dc "partOf") journal]
                [(trig/iri :bibo "volume") (trig/lit (:volume pub))]
                [(trig/iri :bibo "issue") (trig/lit (:issue pub))]
      ))))

(defn arm-rdf
  [prefix data]
  (trig/spo (trig/iri (str prefix (:id data)))
            [(trig/iri :rdf "type") (trig/iri :ontology "Arm")]
            [(trig/iri :rdf "label") (trig/lit (:title data))]))

(defn outcome-timepoint-rdf
  [prefix data]
  (trig/spo (trig/iri (str prefix (:id data)))
            [(trig/iri :rdf "label") (str (:number data) " " (:time_unit data))]))

(defn outcome-rdf
  [prefix data]
  (trig/spo (trig/iri (str prefix (:id data)))
            [(trig/iri :rdf "type") (trig/iri :ontology "Endpoint")]
            [(trig/iri :rdf "label") (trig/lit (:title data))]))

(defn has-nct-id
  [subj nct-id]
  (trig/spo subj [(trig/iri :dc "identifier")
                  (trig/iri (str "http://clinicaltrials.gov/"
                                 (if (.startsWith nct-id "NCT")
                                   nct-id
                                   (str "NCT" nct-id))))]))

(defn has-publication
  [subj pub]
  (trig/spo subj [(trig/iri :ontology "has_publication") pub]))

(defn has-arm
  [subj arm]
  (trig/spo subj [(trig/iri :ontology "has_arm") arm]))

(defn has-outcome
  [subj outcome]
  (trig/spo subj [(trig/iri :ontology "has_outcome") outcome]))

(defn map-vals [m f]
    (into {} (for [[k v] m] [k (f v)])))

(defn uri-for-unique-vals
  [m uri-gen]
  (reduce #(assoc %1 %2 (assoc %2 :uri (uri-gen))) {} (vals m)))

; find all the subgroups reported for this study
; if title and description match, they will be mapped to the same entity 
(defn find-subgroups
  [prefix study-id]
  (let [new-subgroup #(str prefix study-id "/subgroup/" (uuid))
        subgroups (->>
                    (select outcome_subgroups (with outcomes)
                            (fields :id :title :description)
                            (where (= :outcomes.study_id study-id)))
                    (map (fn [entry] { (:id entry) (dissoc entry :id) }))
                    (apply merge))
        unique-subgroups (uri-for-unique-vals subgroups new-subgroup)]
      (map-vals subgroups unique-subgroups)))

; find all the timepoints reported for this study
; if title and description match, they will be mapped to the same entity 
(defn find-timepoints
  [prefix study-id]
  (let [new-timepoint #(str prefix study-id "/timepoint/" (uuid))
        timepoints (->>
                    (select outcome_timepoints (with outcomes)
                            (fields :id :number :time_unit)
                            (where (= :outcomes.study_id study-id)))
                    (map (fn [entry] { (:id entry) (dissoc entry :id) }))
                    (apply merge))
        unique-timepoints (uri-for-unique-vals timepoints new-timepoint)]
      (map-vals timepoints unique-timepoints)))

; find all the measures reported for this study
; if title and description match, they will be mapped to the same entity 
(defn find-measures
  [prefix study-id]
  (let [new-measure #(str prefix study-id "/measure/" (uuid))
        measures (->>
                    (select outcome_measures (with outcome_data_entries)
                            (join outcomes (= :outcome_data_entries.outcome_id :outcomes.id))
                            (fields :id :title :description :unit)
                            (where (= :outcomes.study_id study-id)))
                    (map (fn [entry] { (:id entry) (dissoc entry :id) }))
                    (apply merge))
        unique-measures (uri-for-unique-vals measures new-measure)]
      (map-vals measures unique-measures)))

; find data points
; data points are the values of data attributes.
; the attributes themselves are the outcome_measures.
; most other identifying information is in the outcome_data_entry.
; that is except for the arm, which is stored here.
; some restructuring is needed to match ADDIS.
(defn find-data-points
  [prefix study-id]
  (->>
    (select outcome_data_points (with outcome_measures) (with arms)
            (join outcome_data_entries (= :outcome_data_entries.id :outcome_measures.outcome_data_entry_id))
            (fields :id :value :footnote
                    [:outcome_measure_id :measure_id]
                    :outcome_data_entries.outcome_id
                    :outcome_data_entries.timepoint_id
                    :outcome_data_entries.subgroup_id)
            (where (= :arms.study_id study-id)))
    (map (fn [entry] { (:id entry) (dissoc entry :id) }))
    (apply merge)))

(defn study-rdf
  [prefix study-id]
  (let [study (first (select studies (with primary_publications (fields [:id :pub_id] :trial_title :title)) (with secondary_publications (fields :id)) (with arms) (with outcomes) (where {:id study-id})))
        nct-ids (map :number (select primary_publication_numbers (fields :number) (where {:primary_publication_id (:pub_id study) :number_type "nct"})))
        pmids (select primary_publication_numbers (fields :number) (where {:primary_publication_id (:pub_id study) :number_type "Pubmed"}))
        uri (str prefix study-id)
        subj (trig/spo (trig/iri uri)
                       [(trig/iri :rdf "type") (trig/iri :ontology "Study")]
                       [(trig/iri :rdfs "label") (trig/lit (or (:trial_title study)
                                                               (str "Study " study-id)))]
                       [(trig/iri :rdfs "comment") (trig/lit (:title study))])
        pubs (cons (publication-rdf primary_publications (:pub_id study))
                   (map (fn [pub] (publication-rdf secondary_publications (:id pub)))
                        (:secondary_publications study)))
        pmid-pubs (map (fn [pub] (pmid-rdf (:number pub))) pmids)
        arms (map #(arm-rdf (str uri "/arms/") %) (:arms study))
        arm-uris (map (comp trig/iri #(str uri "/arms/" %) :id) (:arms study))
        outcomes (map #(outcome-rdf (str uri "/outcomes/") %) (:outcomes study))
        outcome-uris (map (comp trig/iri #(str uri "/outcomes/" %) :id) (:outcomes study))
        add-pubs #(reduce has-publication %1 %2)
        add-nct-ids #(reduce has-nct-id %1 nct-ids)
        add-arms #(reduce has-arm % arm-uris)
        add-outcomes #(reduce has-outcome % outcome-uris)
        subgroups (find-subgroups prefix study-id)
        timepoints (find-timepoints prefix study-id)
        measures (find-measures prefix study-id) ; essentially attributes of the measurement
        data-points (find-data-points prefix study-id)]
    (cons
     (-> subj
         (add-pubs pubs)
         (add-pubs pmid-pubs)
         (add-nct-ids)
         (add-arms)
         (add-outcomes))
      (concat arms outcomes)
    )))


(defn -main
  [& args]
  (let [prefixes {:rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                  :rdfs "http://www.w3.org/2000/01/rdf-schema#"
                  :xsd "http://www.w3.org/2001/XMLSchema#"
                  :owl "http://www.w3.org/2002/07/owl#"
                  :qudt "http://qudt.org/schema/qudt#"
                  :ontology "http://trials.drugis.org/ontology#"
                  :dataset "http://trials.drugis.org/datasets/"
                  :study "http://trials.drugis.org/studies/"
                  :instance "http://trials.drugis.org/instances/"
                  :entity "http://trials.drugis.org/entities/"
                  :atc "http://www.whocc.no/ATC2011/"
                  :snomed "http://www.ihtsdo.org/SCT_"
                  :dc "http://purl.org/dc/elements/1.1/" 
                  :bibo "http://purl.org/ontology/bibo/"
                  }
        project-id (Integer/valueOf (first args))
        project (first (select projects
                               (fields :id :title)
                               (with studies (fields :id))
                               (where {:id project-id})))]
    (println)
    (println (trig/write-ttl prefixes
                             (apply concat (map #(study-rdf (str "http://srdr.ahrq.gov/projects/"
                                                   (:id project) "/studies/")
                                              (:id %))
                                  (:studies project)))))))
