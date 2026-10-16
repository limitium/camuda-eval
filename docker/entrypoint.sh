#!/usr/bin/env bash
# Turns a few plain environment variables into the service's settings, so an instance is described
# by its tier, its brokers and its applications — the same variables a k8s config map would carry.
#
#   RULES_ENV            the tier this instance serves: dev, stg, prod            (required)
#   RULES_ROLE           author (edit, release, deploy) | deployer (deploy only)  (default author)
#   RULES_ALLOW_DRAFT    true lets a work space's version be tried here           (default: true on dev)
#   KAFKA_BOOTSTRAP      this tier's brokers, host:port[,host:port]               (required)
#   RULES_APPS           the applications this instance deploys to, space-separated:
#                          name=file,file[@topic]   e.g. "emea-street=pricing,fees amer-street=pricing"
#   RULES_TOPIC_TEMPLATE a topic for an app that names none; {app} and {env} are filled in
#                                                                               (default dmn-{app}-{env})
#   GIT_REMOTE_URL       where every instance meets (file:///git/rules.git, https://…, ssh://…)
#   GIT_USER / GIT_TOKEN credentials for that remote, if it needs them
#   GIT_SYNC_SECONDS     how often to exchange with the remote                  (default 15)
#   RULES_USER           without a proxy in front: who every request is (a demo only)
#   KAFKA_PROPERTIES     extra client properties for every app, space-separated key=value
#                          e.g. "security.protocol=SASL_SSL sasl.mechanism=PLAIN"
#   JAVA_OPTS            JVM options
set -euo pipefail

: "${RULES_ENV:?set RULES_ENV to the tier this instance serves (dev, stg, prod)}"
: "${KAFKA_BOOTSTRAP:?set KAFKA_BOOTSTRAP to the brokers of this tier}"
ROLE="${RULES_ROLE:-author}"
DEFAULT_TEMPLATE='dmn-{app}-{env}'
TEMPLATE="${RULES_TOPIC_TEMPLATE:-$DEFAULT_TEMPLATE}"
if [ -z "${RULES_ALLOW_DRAFT:-}" ]; then
  [ "$RULES_ENV" = "dev" ] && RULES_ALLOW_DRAFT=true || RULES_ALLOW_DRAFT=false
fi

args=(
  "--server.port=7070"
  "--dmn.data.root=/data"
  "--dmn.role=$ROLE"
  "--dmn.deploy.env=$RULES_ENV"
  "--dmn.deploy.allow-draft-publish=$RULES_ALLOW_DRAFT"
)

if [ -n "${GIT_REMOTE_URL:-}" ]; then
  args+=("--dmn.git.enabled=true" "--dmn.git.remote-url=$GIT_REMOTE_URL" "--dmn.git.sync-seconds=${GIT_SYNC_SECONDS:-15}")
  [ -n "${GIT_USER:-}" ] && args+=("--dmn.git.username=$GIT_USER")
  [ -n "${GIT_TOKEN:-}" ] && args+=("--dmn.git.token=$GIT_TOKEN")
fi

if [ -n "${RULES_USER:-}" ]; then
  args+=("--dmn.auth.allow-anonymous=true" "--dmn.auth.anonymous-user=$RULES_USER")
fi

for spec in ${RULES_APPS:-}; do
  name="${spec%%=*}"
  rest="${spec#*=}"
  files="${rest%%@*}"
  if [ "$rest" != "$files" ]; then topic="${rest#*@}"; else topic="${TEMPLATE//\{app\}/$name}"; topic="${topic//\{env\}/$RULES_ENV}"; fi
  p="--dmn.deploy.applications.$name"
  args+=("$p.type=kafka" "$p.bootstrap-servers=$KAFKA_BOOTSTRAP" "$p.topic=$topic" "$p.files=$files")
  for kv in ${KAFKA_PROPERTIES:-}; do
    args+=("$p.properties[${kv%%=*}]=${kv#*=}")
  done
  echo "rules: $name reads ${files} from topic ${topic} on ${KAFKA_BOOTSTRAP}"
done

echo "rules: tier ${RULES_ENV}, role ${ROLE}, drafts ${RULES_ALLOW_DRAFT}, remote ${GIT_REMOTE_URL:-none}"
exec java ${JAVA_OPTS:-} -jar /app/rules.jar "${args[@]}"
