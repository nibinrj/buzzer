-- One database per service, each owned by its own login role (hard rule 4).
-- Runs once, on the first start of an empty postgres-data volume.
-- Passwords are read from the container environment with psql's \getenv (psql 15+),
-- so no secret is stored in this file.
-- REVOKE CONNECT ... FROM PUBLIC: only the owner (and the superuser) can connect.

\getenv identity_pw IDENTITY_DB_PASSWORD
CREATE ROLE identity_app LOGIN PASSWORD :'identity_pw';
CREATE DATABASE identity_db OWNER identity_app;
REVOKE CONNECT ON DATABASE identity_db FROM PUBLIC;

\getenv quiz_pw QUIZ_DB_PASSWORD
CREATE ROLE quiz_app LOGIN PASSWORD :'quiz_pw';
CREATE DATABASE quiz_db OWNER quiz_app;
REVOKE CONNECT ON DATABASE quiz_db FROM PUBLIC;

\getenv session_pw SESSION_DB_PASSWORD
CREATE ROLE session_app LOGIN PASSWORD :'session_pw';
CREATE DATABASE session_db OWNER session_app;
REVOKE CONNECT ON DATABASE session_db FROM PUBLIC;

\getenv scoring_pw SCORING_DB_PASSWORD
CREATE ROLE scoring_app LOGIN PASSWORD :'scoring_pw';
CREATE DATABASE scoring_db OWNER scoring_app;
REVOKE CONNECT ON DATABASE scoring_db FROM PUBLIC;
