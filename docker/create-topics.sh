#!/usr/bin/env bash
# One topic per application on a tier's brokers, made the way deploying needs it: compacted (the
# newest rules per domain are kept forever) and one partition (the rules have a single order).
# Same variables as the instance: KAFKA_BOOTSTRAP, RULES_ENV, RULES_APPS, RULES_TOPIC_TEMPLATE.
set -euo pipefail
DEFAULT_TEMPLATE='dmn-{app}-{env}'
TEMPLATE="${RULES_TOPIC_TEMPLATE:-$DEFAULT_TEMPLATE}"
for spec in ${RULES_APPS:-}; do
  name="${spec%%=*}"
  rest="${spec#*=}"
  files="${rest%%@*}"
  if [ "$rest" != "$files" ]; then topic="${rest#*@}"; else topic="${TEMPLATE//\{app\}/$name}"; topic="${topic//\{env\}/$RULES_ENV}"; fi
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions 1 --replication-factor 1 --config cleanup.policy=compact
  echo "topic $topic on $KAFKA_BOOTSTRAP"
done
