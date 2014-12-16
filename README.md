# srdr-import

Import of SRDR projects into TrialVerse / ADDIS RDF format.

## Usage

`lein run $PROJECT_ID`

## Workings

The SRDR database schema is different in some respects, and at times quite denormalized. The following documents decisions that were made to create reasonably clean imports.

### Projects

Each project (table `projects`) is imported simply with its title, and a description "Imported from SRDR". At this time, only its contained studies are imported along with it. Other potential project level concepts (outcomes, ...) remain to be done.

### Studies

Studies (table `studies`) have a title and description. The title is taken from the first `primary_publications.trial_title` and the description from `primary_publications.title`. In case the title is empty, it is set automatically to "Study X" where X is the database ID of the study.

Note that a study in SRDR is not necessarily a randomized controlled trial, and there is no way to distinguish RCTs from other study types.
This may break some assumptions in ADDIS, but other work is needed before one is able to analyze SRDR data in ADDIS too.
This includes adding design information (i.e. epochs and activities), which are ignored by SRDR because it is a review-oriented system.

### Publications and identifiers

Publications and identifiers are specified in the tables `primary_publications`, `primary_publication_numbers`, and `secondary_publications`. There is also a `secondary_publication_numbers` table, but it is not used.

Although only a single primary publication can be entered for each study, the table allows multiple records, and has a nearly identical structure to the secondary publications table. The secondary publications table has some extra fields of which the purpose is currently unknown. Both tables are handled identically by the importer, and it does not make the primary / secondary distinction.

The publications table contain basic bibliographic information (author, title, journal, etc.), and optionally a PubMed ID (`*_publications.pmid`). In the latter case, it is likely that the bibliographic information was fetched from PubMed by SRDR. Unfortunately that process is not lossless. Therefore, if a PubMed ID is available, the bibliographic information is omitted.

Further publication identifiers may be given in `primary_publication_numbers`. Although the table name suggests otherwise, these are not necessarily alternate identifiers for the primary publication, but may be PubMed IDs of further publications (`number_type = 'Pubmed'`), ClinicalTrials.gov identifiers (`number_type = 'nct'`), or other IDs (variously referred to as `number_type` set to `revman`, `Other`, `internal` or `Internal ID`). PubMed and ClinicalTrials.gov IDs are imported, but other identifiers are discarded.

### Arms and outcomes

Arms (table `arms`) and outcomes (table `outcomes`) are imported simply as an entity with a type and a label. Outcomes are assumed to map onto `ontology:Endpoint` in ADDIS.

### Time points and subgroups

The table `outcome_data_entries` assigns a single identifier to a combination of outcome (table `outcomes`), timepoint (table `outcome_timepoints`), and subgroup (table `outcome_subgroups`). Althought the table also explicitly refers to `study_id` and `extraction_form_id`, there is no indication that these values could differ from the ones specified in `outcomes`.

Thus, to each outcome correspond one or more timepoints and subgroups. It appears that timepoints are never reused between outcomes or studies. However, SRDR assumes throughout that if the properties of these variables ([`number`, `time_unit`] for timepoints, [`title`, `description`] for subgroups) are identical, then they represent the same thing. The importer also makes this assumption, generating a single URI for timepoints or subgroups with identical properties within the entire study. Because the `time_unit` field has no clear semantics, the `number` and `time_unit` are currently concatenated into a title for the timepoint.

### Measurement attributes

One or more measurement attributes per entry of `outcome_data_entry` are defined in the table `outcome_measures`. Fields include `title`, `description`, `unit`, and `note`. Again, a multitude of `outcome_measures` may have the same properties within a study, and we collapse these to a single object in the import. Importantly, a `measure_type IN (1,2)` indicates values that were selected from a drop down menu in SRDR. The values are as follows:

    mysql> SELECT title, COUNT(*) AS count FROM outcome_measures WHERE measure_type IN (1,2) GROUP BY title ORDER BY count DESC ;
    +------------------------------------------------------+-------+
    | title                                                | count |
    +------------------------------------------------------+-------+
    | N Analyzed                                           | 33822 |
    | Standard Deviation                                   | 27964 |
    | Mean                                                 | 26519 |
    | Counts                                               |  9305 |
    | N Enrolled                                           |  8767 |
    | Percentage                                           |  5392 |
    | Median                                               |  2084 |
    | 95% Confidence Interval Lower Limit (95% LCI)        |  1835 |
    | 95% Confidence Interval Upper Limit (95% HCI)        |  1803 |
    | 25th Percentile                                      |  1543 |
    | 75th Percentile                                      |  1543 |
    | Standard Error                                       |  1261 |
    | Proportions                                          |   550 |
    | Max                                                  |   540 |
    | Min                                                  |   540 |
    | Value                                                |   228 |
    | Unit                                                 |   118 |
    | Least Squares Mean                                   |   109 |
    | N                                                    |   107 |
    | Events per Kaplan Meier estimates                    |    80 |
    | Proportion                                           |    22 |
    | Geometric Mean                                       |    10 |
    | Follow-up Median                                     |     7 |
    | Hazard Ratio (HR)                                    |     5 |
    | 90% Confidence Interval Lower Limit (90% LCI)        |     4 |
    | Follow-up Range                                      |     3 |
    | 90% Confidence Interval Upper Limit (90% HCI)        |     3 |
    | Annual Rate as per Kaplan Meier estimates            |     2 |
    | Follow-up Mean                                       |     1 |
    | Event X/N                                            |     1 |
    | Incidence (per 1000)                                 |     1 |
    | Log Rank P-Value                                     |     1 |
    | Log Rank Statistic                                   |     1 |
    | Follow-up in person-years per Kaplan Meier estimates |     1 |
    | Annual Rate as per Raw Data                          |     1 |
    | Follow-up in person-years (raw data)                 |     1 |
    | Log Mean                                             |     1 |
    | Mode                                                 |     1 |
    +------------------------------------------------------+-------+
    38 rows in set (0.61 sec)


TODO: map (most of) these values to actual ontological properties.

### Measurements

Measurements in ADDIS are structured as:

    (outcome, timepoint, arm) -> (attribute, value)

The same basic structure is present in the SRDR schema, but it is set up in a more convoluted way, and it has the addition of subgroups. As a preliminary way to handle the latter, the import generates the following structure instead:

    (outcome, timepoint, arm, subgroup) -> (attribute, value)

TODO: come up with a good way to handle subgroups / strata.

The `outcome_data_points` table identifies the arm and the value, and refers to `outcome_measures` for the attribute.
The `outcome_measures` table in turn refers to `outcome_data_entries`, where the outcome, timepoint, and subgroup are identified.
A large join and mapping of database IDs to their URIs completes the process.

### Adverse events

TODO.

### Quality ratings

TODO.

### More

TODO: find out what more remains to be mapped.

## License

Copyright (c) 2014 Gert van Valkenhoef

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
