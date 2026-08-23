#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <source-topic> [dlt-topic]" >&2
  echo "Example: MAX_MESSAGES=10 $0 request.triaged.v1" >&2
  exit 2
fi

SOURCE_TOPIC="$1"
DLT_TOPIC="${2:-${SOURCE_TOPIC}.dlt}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-pulsaride-kafka}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
MAX_MESSAGES="${MAX_MESSAGES:-10}"
TIMEOUT_MS="${TIMEOUT_MS:-5000}"
TMP_FILE="$(mktemp)"

cleanup() {
  rm -f "$TMP_FILE"
}
trap cleanup EXIT

echo "Reading up to ${MAX_MESSAGES} message(s) from ${DLT_TOPIC}..."
set +e
docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic "$DLT_TOPIC" \
  --from-beginning \
  --timeout-ms "$TIMEOUT_MS" \
  --max-messages "$MAX_MESSAGES" \
  --formatter kafka.tools.DefaultMessageFormatter \
  --property print.key=true \
  --property print.value=true \
  --property key.separator='|' \
  > "$TMP_FILE"
CONSUMER_STATUS=$?
set -e

if [[ ! -s "$TMP_FILE" ]]; then
  echo "No replayable messages found in ${DLT_TOPIC}."
  exit 0
fi

REPLAY_COUNT="$(wc -l < "$TMP_FILE" | tr -d ' ')"
echo "Replaying ${REPLAY_COUNT} message(s) to ${SOURCE_TOPIC}..."
docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic "$SOURCE_TOPIC" \
  --property parse.key=true \
  --property key.separator='|' \
  < "$TMP_FILE"

if [[ "$CONSUMER_STATUS" -ne 0 ]]; then
  echo "Replay completed, but the consumer exited with status ${CONSUMER_STATUS} after timeout/end-of-read."
else
  echo "Replay completed."
fi
