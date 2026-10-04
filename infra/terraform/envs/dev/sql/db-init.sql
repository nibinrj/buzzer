-- One database per service, each owned by its own login role: the AWS counterpart of
-- infra/local/postgres/init/01-databases.sql, run by the one-off db-init task (data.tf) as the RDS master user.
--
-- Differences from the local script:
--   * Idempotent (\gexec runs a CREATE only when the role or database is missing), so a second run is harmless and
--     re-applies the current passwords.
--   * GRANT <role> TO CURRENT_USER: the RDS master is not a superuser, and PostgreSQL 16 lets it make a role a
--     database's owner only if it may SET ROLE to it. Creating the role gives it ADMIN on the role, not that.
--   * VERBOSITY terse: an error prints only its message, never the statement it came from, so a password can't end
--     up in the task's CloudWatch logs.
-- Passwords come from the task's environment (Secrets Manager) through psql's \getenv: none is stored here.

\set VERBOSITY terse

\getenv identity_pw IDENTITY_DB_PASSWORD
SELECT format('CREATE ROLE identity_app LOGIN PASSWORD %L', :'identity_pw')
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'identity_app')\gexec
ALTER ROLE identity_app PASSWORD :'identity_pw';
GRANT identity_app TO CURRENT_USER;
SELECT 'CREATE DATABASE identity_db OWNER identity_app'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'identity_db')\gexec
REVOKE CONNECT ON DATABASE identity_db FROM PUBLIC;

\getenv quiz_pw QUIZ_DB_PASSWORD
SELECT format('CREATE ROLE quiz_app LOGIN PASSWORD %L', :'quiz_pw')
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'quiz_app')\gexec
ALTER ROLE quiz_app PASSWORD :'quiz_pw';
GRANT quiz_app TO CURRENT_USER;
SELECT 'CREATE DATABASE quiz_db OWNER quiz_app'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'quiz_db')\gexec
REVOKE CONNECT ON DATABASE quiz_db FROM PUBLIC;

\getenv session_pw SESSION_DB_PASSWORD
SELECT format('CREATE ROLE session_app LOGIN PASSWORD %L', :'session_pw')
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'session_app')\gexec
ALTER ROLE session_app PASSWORD :'session_pw';
GRANT session_app TO CURRENT_USER;
SELECT 'CREATE DATABASE session_db OWNER session_app'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'session_db')\gexec
REVOKE CONNECT ON DATABASE session_db FROM PUBLIC;

\getenv scoring_pw SCORING_DB_PASSWORD
SELECT format('CREATE ROLE scoring_app LOGIN PASSWORD %L', :'scoring_pw')
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'scoring_app')\gexec
ALTER ROLE scoring_app PASSWORD :'scoring_pw';
GRANT scoring_app TO CURRENT_USER;
SELECT 'CREATE DATABASE scoring_db OWNER scoring_app'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'scoring_db')\gexec
REVOKE CONNECT ON DATABASE scoring_db FROM PUBLIC;
