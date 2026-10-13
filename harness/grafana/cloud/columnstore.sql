-- The public DB's run-sample tables as TimescaleDB columnstore hypertables (2026-10-12): 1-day chunks on t, compressed
-- per run ordered by rt, so a Run dashboard query reads one run's compressed batches. The SQL of dashboards.py and
-- ingest.py is unchanged (DELETE ... WHERE run = / COPY work on compressed chunks; a policy recompresses what a re-import
-- left uncompressed). Remote only (the local DB stays plain Postgres). Idempotent: converts the tables that are still plain,
-- one transaction each (the old table is dropped once its rows are in). Needs the extension on the VM
-- (timescaledb-2-postgresql-15, shared_preload_libraries = 'timescaledb'). `stacks` stays plain: the hourly stack_defs
-- prune looks rows up by stack_id, which would decompress every chunk.
--   harness/grafana/cloud.sh columnstore     (cloud.sh seed runs it after schema.sql)
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE OR REPLACE PROCEDURE pzopt_columnstore(tables text[])
LANGUAGE plpgsql AS $$
DECLARE
  tbl text;
  n bigint;
  t0 timestamptz;
BEGIN
  PERFORM set_config('timescaledb.enable_direct_compress_insert', 'on', false);
  FOREACH tbl IN ARRAY tables LOOP
    CONTINUE WHEN EXISTS (SELECT 1 FROM timescaledb_information.hypertables WHERE hypertable_schema = 'public' AND hypertable_name = tbl);
    CONTINUE WHEN to_regclass(format('public.%I', tbl)) IS NULL;
    t0 := clock_timestamp();
    EXECUTE format('DROP TABLE IF EXISTS %I', tbl || '_cs');
    EXECUTE format('CREATE TABLE %I (LIKE %I INCLUDING DEFAULTS)', tbl || '_cs', tbl);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN t SET NOT NULL', tbl || '_cs');
    PERFORM create_hypertable(format('public.%I', tbl || '_cs')::regclass, by_range('t', INTERVAL '1 day'), create_default_indexes => false);
    EXECUTE format('ALTER TABLE %I SET (timescaledb.compress, timescaledb.compress_segmentby = %L, timescaledb.compress_orderby = %L)',
                   tbl || '_cs', 'run', 'rt');
    EXECUTE format('INSERT INTO %I SELECT * FROM %I', tbl || '_cs', tbl);
    GET DIAGNOSTICS n = ROW_COUNT;
    EXECUTE format('DROP TABLE %I', tbl);
    EXECUTE format('ALTER TABLE %I RENAME TO %I', tbl || '_cs', tbl);
    EXECUTE format('CREATE INDEX %I ON %I (run, rt)', tbl || '_run_rt', tbl);  -- the uncompressed rows of recent chunks
    EXECUTE format('GRANT SELECT ON %I TO grafana', tbl);
    PERFORM add_compression_policy(format('public.%I', tbl)::regclass, compress_after => INTERVAL '12 hours');
    RAISE NOTICE '% : % rows, % in %', tbl, n, pg_size_pretty(hypertable_size(format('public.%I', tbl)::regclass)),
                 date_trunc('second', clock_timestamp() - t0);
    COMMIT;
  END LOOP;
END $$;

CALL pzopt_columnstore(ARRAY['power', 'sysmon', 'events', 'chunks', 'gamethread', 'series', 'frames', 'pacing', 'present', 'overlay']);
