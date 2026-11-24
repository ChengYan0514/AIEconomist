CREATE TABLE sources (id varchar(255) PRIMARY KEY, display_name text);
CREATE TABLE works (id varchar(255) PRIMARY KEY, publication_year integer, title text, doi text);
CREATE TABLE works_locations (work_id varchar(255), source_id varchar(255));
CREATE TABLE works_authorships (work_id varchar(255), author_id varchar(255));
CREATE TABLE authors (id varchar(255) PRIMARY KEY, display_name text);
CREATE TABLE topics (
    id varchar(255) PRIMARY KEY,
    display_name text,
    field_display_name text,
    subfield_display_name text,
    domain_display_name text
);
CREATE TABLE works_topics (work_id varchar(255), topic_id varchar(255));

INSERT INTO sources VALUES ('S123', 'Review of Applied Economics'), ('S124', 'Journal of Urban Economics');
INSERT INTO works VALUES
    ('W123', 2024, 'A Test Paper', 'https://doi.org/10.1/test'),
    ('W124', 2024, 'Ambiguous Study', '10.1/amb'),
    ('W125', 2024, 'Ambiguous Study, Alternative', '10.1/amb');
INSERT INTO works_locations VALUES ('W123', 'S123'), ('W124', 'S123'), ('W125', 'S123');
INSERT INTO authors VALUES ('A1', 'Alice'), ('A2', 'Bob');
INSERT INTO works_authorships VALUES ('W123', 'A1'), ('W123', 'A2'), ('W124', 'A1'), ('W125', 'A2');
INSERT INTO topics VALUES
    ('T1', 'Monetary Policy Transmission', 'Economics, Econometrics and Finance', 'Macroeconomics', 'Social Sciences'),
    ('T2', 'Minimum Wage Policy', 'Economics, Econometrics and Finance', 'Labor Economics', 'Social Sciences');
INSERT INTO works_topics VALUES ('W123', 'T1'), ('W124', 'T2');
