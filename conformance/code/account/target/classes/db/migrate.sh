#!/bin/sh

"${FLYWAY_BIN:-flyway}" \
  -url="$DB_URL" \
  -table="$SCHEMA_TABLE" \
  -user="$FLYWAY_USER" \
  -password="$FLYWAY_PASSWORD" \
  -locations="$FLYWAY_LOCATIONS" \
  -schemas="$FLYWAY_SCHEMAS" \
  -defaultSchema="$FLYWAY_DEFAULT_SCHEMA" \
  -baselineOnMigrate=true \
  -outOfOrder=true \
  migrate