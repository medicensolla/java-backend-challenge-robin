-- Expand only: retain name for old readers and writers throughout the rollout.
ALTER TABLE coaches ADD COLUMN first_name TEXT;
ALTER TABLE coaches ADD COLUMN last_name TEXT;

CREATE FUNCTION normalize_coach_name(value TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE STRICT
AS $$
    SELECT btrim(regexp_replace(value, E'[ \t\n\r\f\x0B]+', ' ', 'g'));
$$;

WITH normalized AS (
    SELECT id, normalize_coach_name(name) AS name FROM coaches
)
UPDATE coaches AS coach
SET name = normalized.name,
    first_name = split_part(normalized.name, ' ', 1),
    last_name = CASE WHEN strpos(normalized.name, ' ') = 0 THEN ''
                     ELSE substr(normalized.name, strpos(normalized.name, ' ') + 1) END
FROM normalized
WHERE coach.id = normalized.id;

CREATE FUNCTION synchronize_coach_name() RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    name_changed BOOLEAN;
    parts_changed BOOLEAN;
    normalized_name TEXT;
    normalized_parts TEXT;
    canonical_name TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        name_changed := NEW.name IS NOT NULL;
        parts_changed := NEW.first_name IS NOT NULL OR NEW.last_name IS NOT NULL;
    ELSE
        name_changed := NEW.name IS DISTINCT FROM OLD.name;
        parts_changed := NEW.first_name IS DISTINCT FROM OLD.first_name
                      OR NEW.last_name IS DISTINCT FROM OLD.last_name;
    END IF;

    IF NOT name_changed AND NOT parts_changed THEN
        RETURN NEW;
    END IF;

    normalized_name := normalize_coach_name(NEW.name);
    normalized_parts := normalize_coach_name(NEW.first_name || ' ' || NEW.last_name);
    IF name_changed AND parts_changed AND normalized_name IS DISTINCT FROM normalized_parts THEN
        RAISE EXCEPTION 'Coach name representations must agree.' USING ERRCODE = '23514';
    END IF;

    canonical_name := CASE WHEN parts_changed THEN normalized_parts ELSE normalized_name END;
    NEW.name := canonical_name;
    NEW.first_name := split_part(canonical_name, ' ', 1);
    NEW.last_name := CASE WHEN strpos(canonical_name, ' ') = 0 THEN ''
                         ELSE substr(canonical_name, strpos(canonical_name, ' ') + 1) END;
    RETURN NEW;
END;
$$;

CREATE TRIGGER synchronize_coach_name
BEFORE INSERT OR UPDATE ON coaches
FOR EACH ROW EXECUTE FUNCTION synchronize_coach_name();

ALTER TABLE coaches ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE coaches ALTER COLUMN last_name SET NOT NULL;
